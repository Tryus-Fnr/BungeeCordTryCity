package net.md_5.bungee.seamless;

import com.google.common.collect.MapMaker;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.regex.Pattern;
import net.md_5.bungee.BungeeCord;
import net.md_5.bungee.UserConnection;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.protocol.DefinedPacket;
import net.md_5.bungee.protocol.ProtocolConstants;

/**
 * TryCity: Serverwechsel ohne Ladebildschirm.
 *
 * Der Client bleibt beim Wechsel im PLAY-Zustand. Die Konfigurationsphase des
 * neuen Servers beantwortet der Proxy selbst, Login und Respawn gehen nicht an
 * den Client. Danach räumt der Proxy die Reste des alten Servers weg und
 * schreibt die eigene Entity-ID des Spielers um, weil der Client seine alte ID
 * behält.
 *
 * Abgeschaltet, solange nicht {@code -Dtrycity.seamless=true} gesetzt ist. Ohne
 * Schalter verhält sich der Proxy genau wie normales BungeeCord.
 */
public final class SeamlessSwitch
{

    public static final boolean ENABLED = Boolean.getBoolean( "trycity.seamless" );
    public static final boolean DEBUG = Boolean.getBoolean( "trycity.seamless.debug" );
    public static final long FREEZE_TIMEOUT_MS = Long.getLong( "trycity.seamless.freezeTimeoutMs", 3000L );
    public static final long GHOST_CLEANUP_DELAY_MS = Long.getLong( "trycity.seamless.ghostDelayMs", 5000L );
    // Nur Wechsel zwischen Servern, deren Name hierauf passt (beide Seiten)
    private static final Pattern SERVERS = Pattern.compile( System.getProperty( "trycity.seamless.servers", ".*" ) );
    // Floodgate vergibt Bedrock-Spielern UUIDs mit diesem Anfang
    private static final String FLOODGATE_PREFIX = "00000000-0000-0000-0009";
    // Nachrichten, die der Client nur beim Welt-Join schickt; nach einem nahtlosen Wechsel reicht der Proxy sie nach
    private static final Set<String> REPLAY_CHANNELS = new HashSet<>( Arrays.asList( System.getProperty( "trycity.seamless.replayChannels", "voicechat:request_secret" ).split( "," ) ) );
    // Fingerabdruck der Registries je Zielserver (und Pack-Antwort des Clients) aus der letzten Konfiguration dort.
    // Schlüssel ist das ServerInfo-Objekt selbst (Identität, schwach gehalten), nicht der Name: CloudNet legt beim
    // Start eines Dienstes ein neues an. Ein Stand von vor dem Neustart (z. B. vor einem neuen Datapack) verfällt
    // damit von allein, statt bis zum nächsten normalen Wechsel jeden Versuch zu verhindern.
    // Innerer Schlüssel: Protokoll des Clients + Pack-Antwort. ViaVersion auf dem Backend übersetzt die Registries je
    // Client-Fassung; ohne Protokoll teilten sich Clients mit gleicher Pack-Antwort einen Stand, und wer die andere
    // Fassung hatte, bekam bei jedem Wechsel "Registries dort anders" (Live-Log 28.10.2026).
    private static final ConcurrentMap<ServerInfo, Map<String, ConfigFingerprint>> SERVER_FINGERPRINTS = new MapMaker().weakKeys().makeMap();
    //
    private static volatile boolean floodgateLooked;
    private static volatile Object floodgateApi;
    private static volatile Method floodgateCheck;

    private SeamlessSwitch()
    {
    }

    public static boolean isReplayChannel(String tag)
    {
        return REPLAY_CHANNELS.contains( tag );
    }

    static void rememberFingerprint(ServerInfo server, int protocolVersion, byte[] knownPacksReply, ConfigFingerprint fingerprint)
    {
        if ( server != null && knownPacksReply != null && fingerprint != null )
        {
            SERVER_FINGERPRINTS.computeIfAbsent( server, key -> new ConcurrentHashMap<>() ).put( fingerprintKey( protocolVersion, knownPacksReply ), fingerprint );
        }
    }

    // null = seit dem letzten Start dieses Servers noch nie mit dieser Client-Fassung dort konfiguriert
    static ConfigFingerprint knownFingerprint(ServerInfo server, int protocolVersion, byte[] knownPacksReply)
    {
        Map<String, ConfigFingerprint> known = server == null ? null : SERVER_FINGERPRINTS.get( server );
        return known == null ? null : known.get( fingerprintKey( protocolVersion, knownPacksReply ) );
    }

    static String fingerprintKey(int protocolVersion, byte[] knownPacksReply)
    {
        return protocolVersion + ":" + Arrays.hashCode( knownPacksReply );
    }

    // Nur für diese Protokollfassungen sind die Paketnummern unten geprüft (26.1.x und 26.2)
    public static boolean supportsVersion(int protocolVersion)
    {
        return protocolVersion == ProtocolConstants.MINECRAFT_26_1 || protocolVersion == ProtocolConstants.MINECRAFT_26_2;
    }

    public static boolean serverAllowed(String name)
    {
        return name != null && SERVERS.matcher( name ).matches();
    }

    public static boolean isBedrock(UUID uuid)
    {
        return uuid != null && uuid.toString().startsWith( FLOODGATE_PREFIX );
    }

    // Auch Bedrock-Spieler mit verknüpftem Java-Konto (Java-UUID): bei Floodgate selbst nachfragen
    public static boolean isFloodgatePlayer(UUID uuid)
    {
        if ( isBedrock( uuid ) )
        {
            return true;
        }
        if ( !floodgateLooked )
        {
            lookUpFloodgate();
        }
        if ( floodgateCheck == null || uuid == null )
        {
            return false;
        }
        try
        {
            return Boolean.TRUE.equals( floodgateCheck.invoke( floodgateApi, uuid ) );
        } catch ( ReflectiveOperationException | RuntimeException ex )
        {
            return false;
        }
    }

    private static synchronized void lookUpFloodgate()
    {
        if ( floodgateLooked )
        {
            return;
        }
        floodgateLooked = true;
        Plugin plugin = ProxyServer.getInstance().getPluginManager().getPlugin( "floodgate" );
        if ( plugin == null )
        {
            return;
        }
        try
        {
            Class<?> api = Class.forName( "org.geysermc.floodgate.api.FloodgateApi", true, plugin.getClass().getClassLoader() );
            floodgateApi = api.getMethod( "getInstance" ).invoke( null );
            floodgateCheck = api.getMethod( "isFloodgatePlayer", UUID.class );
        } catch ( ReflectiveOperationException | RuntimeException | LinkageError ex )
        {
            BungeeCord.getInstance().getLogger().log( Level.WARNING, "[seamless] Floodgate-API nicht nutzbar, erkenne Bedrock nur an der UUID", ex );
        }
    }

    public static void debug(UserConnection con, String message)
    {
        if ( DEBUG )
        {
            BungeeCord.getInstance().getLogger().log( Level.INFO, "[seamless] {0}: {1}", new Object[]
            {
                con.getName(), message
            } );
        }
    }

    public static void info(UserConnection con, String message)
    {
        BungeeCord.getInstance().getLogger().log( Level.INFO, "[seamless] {0}: {1}", new Object[]
        {
            con.getName(), message
        } );
    }

    /*========================================================================*/
    // Hilfen für rohe Pakete (Paket-Nummer + Nutzlast, ohne Längenpräfix)

    public static ByteBuf packet(int packetId)
    {
        ByteBuf buf = Unpooled.buffer();
        DefinedPacket.writeVarInt( packetId, buf );
        return buf;
    }

    public static int peekPacketId(ByteBuf buf)
    {
        int index = buf.readerIndex();
        try
        {
            return DefinedPacket.readVarInt( buf );
        } finally
        {
            buf.readerIndex( index );
        }
    }

    public static long chunkKey(int x, int z)
    {
        return ( x & 0xFFFFFFFFL ) | ( ( z & 0xFFFFFFFFL ) << 32 );
    }

    public static int chunkX(long key)
    {
        return (int) key;
    }

    public static int chunkZ(long key)
    {
        return (int) ( key >>> 32 );
    }

    public static int varIntSize(int value)
    {
        int size = 1;
        while ( ( value & ~0x7F ) != 0 )
        {
            size++;
            value >>>= 7;
        }
        return size;
    }

    /*========================================================================*/

    /**
     * Paketnummern für Protokoll 775 (Minecraft 26.1.x), aus dem Datengenerator
     * von 26.1.2 und gegen den Client-Code geprüft. Für 776 (26.2) gleich: Paket-
     * Reihenfolge in GameProtocols/ConfigurationProtocols, Metadaten-Serializer und
     * -Indizes stimmen in beiden Client-Jars überein. Nur die Entity-Typen verschieben
     * sich, siehe {@link #entityType}.
     */
    public static final class Ids
    {

        private Ids()
        {
        }

        // 26.2 fügt sulfur_cube als Entity-Typ 131 ein, alle Typen ab dort rücken um eins weiter
        private static final int SULFUR_CUBE_26_2 = 131;

        // Entity-Typ-ID aus 26.1 (Konstanten unten) in die des Client-Protokolls umrechnen
        public static int entityType(int type26x1, int protocol)
        {
            if ( protocol >= ProtocolConstants.MINECRAFT_26_2 && type26x1 >= SULFUR_CUBE_26_2 )
            {
                return type26x1 + 1;
            }
            return type26x1;
        }
        // PLAY, Server -> Client
        public static final int ADD_ENTITY = 1;
        public static final int ANIMATE = 2;
        public static final int BLOCK_DESTRUCTION = 5;
        public static final int CONTAINER_CLOSE = 17;
        public static final int COOLDOWN = 22;
        public static final int CUSTOM_PAYLOAD = 24;
        public static final int DAMAGE_EVENT = 25;
        public static final int ENTITY_EVENT = 34;
        public static final int ENTITY_POSITION_SYNC = 35;
        public static final int FORGET_LEVEL_CHUNK = 37;
        public static final int GAME_EVENT = 38;
        public static final int HURT_ANIMATION = 42;
        public static final int LEVEL_CHUNK_WITH_LIGHT = 45;
        public static final int MOVE_ENTITY_POS = 53;
        public static final int MOVE_ENTITY_POS_ROT = 54;
        public static final int MOVE_MINECART = 55;
        public static final int MOVE_ENTITY_ROT = 56;
        public static final int OPEN_SCREEN = 59;
        public static final int PLAYER_ABILITIES = 64;
        public static final int PLAYER_CHAT = 65;
        public static final int PLAYER_COMBAT_KILL = 68;
        public static final int PLAYER_LOOK_AT = 71;
        public static final int PLAYER_POSITION = 72;
        public static final int REMOVE_ENTITIES = 77;
        public static final int REMOVE_MOB_EFFECT = 78;
        public static final int RESOURCE_PACK_POP = 80;
        public static final int RESOURCE_PACK_PUSH = 81;
        public static final int RESPAWN = 82;
        public static final int ROTATE_HEAD = 83;
        public static final int SET_CAMERA = 93;
        public static final int SET_CHUNK_CACHE_CENTER = 94;
        public static final int SET_ENTITY_DATA = 99;
        public static final int SET_ENTITY_LINK = 100;
        public static final int SET_ENTITY_MOTION = 101;
        public static final int SET_EQUIPMENT = 102;
        public static final int SET_HEALTH = 104;
        public static final int SET_PASSENGERS = 107;
        public static final int SET_SIMULATION_DISTANCE = 111;
        public static final int SOUND_ENTITY = 116;
        public static final int START_CONFIGURATION = 118;
        public static final int STORE_COOKIE = 120;
        public static final int TAKE_ITEM_ENTITY = 124;
        public static final int TELEPORT_ENTITY = 125;
        public static final int UPDATE_ATTRIBUTES = 131;
        public static final int UPDATE_MOB_EFFECT = 132;
        public static final int PROJECTILE_POWER = 135;
        public static final int CUSTOM_REPORT_DETAILS = 136;
        public static final int SERVER_LINKS = 137;
        public static final int WAYPOINT = 138;
        public static final int CLEAR_DIALOG = 139;
        // PLAY, Client -> Server
        public static final int SB_ATTACK = 1;
        public static final int SB_CHAT_SESSION_UPDATE = 10;
        public static final int SB_ENTITY_TAG_QUERY = 25;
        public static final int SB_INTERACT = 26;
        public static final int SB_PICK_ITEM_FROM_ENTITY = 37;
        public static final int SB_PLAYER_ABILITIES = 40;
        public static final int SB_PLAYER_COMMAND = 42;
        public static final int SB_PLAYER_LOADED = 44;
        public static final int SB_RESOURCE_PACK = 49;
        // CONFIGURATION, Server -> Client
        public static final int CFG_COOKIE_REQUEST = 0;
        public static final int CFG_CUSTOM_PAYLOAD = 1;
        public static final int CFG_PING = 5;
        public static final int CFG_RESET_CHAT = 6;
        public static final int CFG_REGISTRY_DATA = 7;
        public static final int CFG_RESOURCE_PACK_POP = 8;
        public static final int CFG_RESOURCE_PACK_PUSH = 9;
        public static final int CFG_STORE_COOKIE = 10;
        public static final int CFG_TRANSFER = 11;
        public static final int CFG_UPDATE_ENABLED_FEATURES = 12;
        public static final int CFG_UPDATE_TAGS = 13;
        public static final int CFG_CUSTOM_REPORT_DETAILS = 15;
        public static final int CFG_SERVER_LINKS = 16;
        public static final int CFG_CLEAR_DIALOG = 17;
        public static final int CFG_SHOW_DIALOG = 18;
        public static final int CFG_CODE_OF_CONDUCT = 19;
        // CONFIGURATION, Client -> Server
        public static final int CFG_SB_PONG = 5;
        public static final int CFG_SB_RESOURCE_PACK = 6;
        public static final int CFG_SB_SELECT_KNOWN_PACKS = 7;
        public static final int CFG_SB_FINISH = 3;
        // Entity-Typ firework_rocket; Metadaten-Index 9 (OPTIONAL_UNSIGNED_INT = 19) = Spieler, an dem sie hängt
        public static final int FIREWORK_ROCKET_TYPE = 53;
        public static final int FIREWORK_ATTACHED_INDEX = 9;
        public static final int SERIALIZER_OPTIONAL_UNSIGNED_INT = 19;
        // Entity-Typen (Nummern aus 26.1), deren add_entity-"data" die ID des Besitzers trägt
        public static final int[] OWNER_DATA_TYPES =
        {
            6, 123, 135, 39, 120, 44, 48, 105, 106, 52, 118, 37, 147, 143, 18, 79, 113, 53, 156
        };
    }
}
