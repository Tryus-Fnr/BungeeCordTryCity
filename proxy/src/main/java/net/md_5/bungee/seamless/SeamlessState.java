package net.md_5.bungee.seamless;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.md_5.bungee.BungeeCord;
import net.md_5.bungee.BungeeServerInfo;
import net.md_5.bungee.ServerConnection;
import net.md_5.bungee.UserConnection;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.score.Objective;
import net.md_5.bungee.api.score.Score;
import net.md_5.bungee.api.score.Scoreboard;
import net.md_5.bungee.api.score.Team;
import net.md_5.bungee.connection.UpstreamBridge;
import net.md_5.bungee.protocol.DefinedPacket;
import net.md_5.bungee.protocol.PacketWrapper;
import net.md_5.bungee.protocol.Protocol;
import net.md_5.bungee.protocol.packet.BossBar;
import net.md_5.bungee.protocol.packet.BundleDelimiter;
import net.md_5.bungee.protocol.packet.EntityStatus;
import net.md_5.bungee.protocol.packet.FinishConfiguration;
import net.md_5.bungee.protocol.packet.GameState;
import net.md_5.bungee.protocol.packet.KnownPacks;
import net.md_5.bungee.protocol.packet.Login;
import net.md_5.bungee.protocol.packet.PluginMessage;
import net.md_5.bungee.protocol.packet.Respawn;
import net.md_5.bungee.protocol.packet.ScoreboardObjective;
import net.md_5.bungee.protocol.packet.ScoreboardScoreReset;
import net.md_5.bungee.protocol.packet.StartConfiguration;
import net.md_5.bungee.protocol.packet.ViewDistance;
import net.md_5.bungee.protocol.util.Either;
import net.md_5.bungee.seamless.SeamlessSwitch.Ids;

/**
 * Zustand eines Spielers für den Wechsel ohne Ladebildschirm.
 *
 * Ablauf eines Wechsels:
 * <ol>
 * <li>ABSORBING: Der neue Server konfiguriert, der Proxy antwortet an Stelle
 * des Clients. Alles wird zwischengespeichert.</li>
 * <li>Passt etwas nicht (andere Registries/Tags, neues Resourcepack, Cookie,
 * Transfer, Dialog), geht es mit FALLBACK normal weiter: StartConfiguration an
 * den Client und alles Gespeicherte hinterher.</li>
 * <li>Sonst AWAIT_LOGIN: FinishConfiguration geht an den Server, Login wird
 * abgefangen, Reste des alten Servers werden entfernt.</li>
 * <li>FROZEN: Der Spieler schwebt, bis der Chunk unter ihm da ist.</li>
 * </ol>
 *
 * Alle Aufrufe kommen aus derselben Netty-Schleife: BungeeCord legt die
 * Verbindung zum Backend auf die Ereignisschleife des Clients.
 */
public final class SeamlessState
{

    private enum Phase
    {

        IDLE, ABSORBING, FALLBACK, AWAIT_LOGIN, FROZEN;
    }
    //
    private final UserConnection con;
    private boolean active;
    private Phase phase = Phase.IDLE;
    private ServerConnection target;
    private int generation;
    // Aus der letzten echten Konfiguration
    private byte[] clientKnownPacksReply;
    private byte[] clientChatSession;
    private ConfigFingerprint configDigest;
    private ConfigFingerprint realFingerprint;
    private final Set<String> loadedPacks = new HashSet<>();
    private final Map<UUID, String> pushedPacks = new HashMap<>();
    // Laufender Versuch
    private final List<Object> buffered = new ArrayList<>();
    private boolean answeredKnownPacks;
    private final Set<UUID> answeredPacks = new HashSet<>();
    private String fallbackReason;
    private boolean fallbackAckPending;
    // Was der Client gerade sieht
    private final Set<Integer> entities = new HashSet<>();
    private final Set<Long> chunks = new HashSet<>();
    private final Set<Integer> ownEffects = new HashSet<>();
    private final Set<String> cooldowns = new HashSet<>();
    private final Map<String, byte[]> waypoints = new HashMap<>();
    private final Set<Integer> fireworks = new HashSet<>();
    private final Map<String, byte[]> replayMessages = new LinkedHashMap<>();
    private int openContainer = -1;
    private boolean signedChatSeen;
    private boolean dead;
    private boolean centerKnown;
    private int centerX;
    private int centerZ;
    private int viewDistance = 10;
    // Schweben nach dem Wechsel
    private byte[] heldAbilities;
    private byte[] heldGameMode;
    private boolean arrivalKnown;
    private long arrivalChunk;
    private Set<Long> ghostChunks;

    public SeamlessState(UserConnection con)
    {
        this.con = con;
    }

    public void init()
    {
        active = SeamlessSwitch.ENABLED
                && SeamlessSwitch.supportsVersion( con.getPendingConnection().getVersion() )
                && !SeamlessSwitch.isBedrock( con.getUniqueId() );
    }

    public boolean isActive()
    {
        return active;
    }

    /*========================================================================*/
    // Beginn eines Wechsels (ServerConnector.cutThrough)

    // Wird bei jedem Wechsel aufgerufen; true = nahtlos versuchen, kein StartConfiguration senden
    public boolean tryBegin(BungeeServerInfo targetInfo)
    {
        configDigest = null;
        if ( !active )
        {
            if ( SeamlessSwitch.ENABLED )
            {
                logNormalSwitch( targetInfo.getName(), "für diesen Client aus (Protokoll " + con.getPendingConnection().getVersion() + " oder Bedrock-UUID)" );
            }
            return false;
        }
        if ( phase == Phase.FROZEN )
        {
            release( false );
        } else if ( phase != Phase.IDLE )
        {
            reset( "neuer Wechsel" );
        }

        ServerConnection current = con.getServer();
        String reason = null;
        ConfigFingerprint known = clientKnownPacksReply == null ? null : SeamlessSwitch.knownFingerprint( targetInfo, clientKnownPacksReply );
        if ( current == null )
        {
            reason = "kein bisheriger Server";
        } else if ( !SeamlessSwitch.serverAllowed( current.getInfo().getName() ) || !SeamlessSwitch.serverAllowed( targetInfo.getName() ) )
        {
            reason = "Server nicht freigegeben";
        } else if ( SeamlessSwitch.isFloodgatePlayer( con.getUniqueId() ) )
        {
            reason = "Bedrock-Spieler";
        } else if ( con.getForgeClientHandler().isForgeUser() )
        {
            reason = "Forge-Client";
        } else if ( clientKnownPacksReply == null || realFingerprint == null )
        {
            reason = "noch keine Vergleichsdaten";
        } else if ( known != null && !known.sameAs( realFingerprint ) )
        {
            // Schon bekannt, dass dort andere Registries laufen: gar nicht erst versuchen,
            // sonst kommt der Rückfall erst nach ServerSwitchEvent, während Plugins den Client im Spiel sehen
            reason = "Registries dort anders: " + known.describeDifference( realFingerprint );
        } else if ( signedChatSeen )
        {
            reason = "Client kennt signierte Chatnachrichten";
        } else if ( dead )
        {
            // Den Todesbildschirm schließt nur ein Respawn
            reason = "Spieler ist tot";
        } else if ( con.getDimension() == null )
        {
            reason = "keine Dimension bekannt";
        }
        if ( reason != null )
        {
            logNormalSwitch( targetInfo.getName(), reason );
            return false;
        }

        phase = Phase.ABSORBING;
        generation++;
        target = null;
        configDigest = new ConfigFingerprint();
        releaseBuffered();
        answeredKnownPacks = false;
        answeredPacks.clear();
        fallbackReason = null;
        fallbackAckPending = false;
        SeamlessSwitch.debug( con, "versuche nahtlosen Wechsel nach " + targetInfo.getName() );
        return true;
    }

    // Eine Zeile je Wechsel, der gar nicht erst nahtlos versucht wird; sonst sieht man im Log nicht, warum
    public void logNormalSwitch(String targetName, String reason)
    {
        SeamlessSwitch.info( con, "normaler Wechsel nach " + targetName + ": " + reason );
    }

    // Nach dem Umhängen auf die DownstreamBridge: dem Backend wie der Client antworten
    public void startAbsorb(ServerConnection server)
    {
        target = server;
        UpstreamBridge.configureBackend( con );
    }

    public boolean absorbing(ServerConnection server)
    {
        return phase == Phase.ABSORBING && target == server;
    }

    public boolean awaitingSeamlessLogin(ServerConnection server)
    {
        return phase == Phase.AWAIT_LOGIN && target == server;
    }

    /*========================================================================*/
    // Konfigurationsphase des neuen Servers abfangen

    // Rohes Konfig-Paket vom Backend während ABSORBING
    public void absorb(PacketWrapper packet)
    {
        ByteBuf buf = packet.buf.duplicate();
        int id = DefinedPacket.readVarInt( buf );
        if ( packet.protocol == Protocol.CONFIGURATION )
        {
            switch ( id )
            {
                case Ids.CFG_PING:
                    ByteBuf pong = SeamlessSwitch.packet( Ids.CFG_SB_PONG );
                    pong.writeInt( buf.readInt() );
                    target.getCh().write( pong );
                    return;
                case Ids.CFG_REGISTRY_DATA:
                case Ids.CFG_UPDATE_TAGS:
                case Ids.CFG_UPDATE_ENABLED_FEATURES:
                    digest( packet.buf );
                    break;
                case Ids.CFG_RESOURCE_PACK_PUSH:
                    absorbResourcePack( buf );
                    break;
                case Ids.CFG_COOKIE_REQUEST:
                case Ids.CFG_TRANSFER:
                case Ids.CFG_SHOW_DIALOG:
                case Ids.CFG_CODE_OF_CONDUCT:
                    fallbackReason = "Konfig-Paket " + id + " braucht den Client";
                    break;
                default:
                    break;
            }
        }
        // Heap-Kopie: geht nichts verloren, wenn der Versuch ohne Aufräumen endet
        buffered.add( Unpooled.copiedBuffer( packet.buf ) );
        if ( fallbackReason != null )
        {
            startFallback();
        }
    }

    private void absorbResourcePack(ByteBuf buf)
    {
        UUID uuid = DefinedPacket.readUUID( buf );
        DefinedPacket.readString( buf );
        String hash = DefinedPacket.readString( buf );
        pushedPacks.put( uuid, hash );
        if ( !loadedPacks.contains( packKey( uuid, hash ) ) )
        {
            fallbackReason = "neues Resourcepack";
            return;
        }
        // Der Client hat genau dieses Pack schon geladen: wie der Client antworten
        sendPackStatus( uuid, 3 );
        sendPackStatus( uuid, 4 );
        sendPackStatus( uuid, 0 );
        answeredPacks.add( uuid );
    }

    private void sendPackStatus(UUID uuid, int action)
    {
        ByteBuf status = SeamlessSwitch.packet( Ids.CFG_SB_RESOURCE_PACK );
        DefinedPacket.writeUUID( uuid, status );
        DefinedPacket.writeVarInt( action, status );
        target.getCh().write( status );
    }

    // DownstreamBridge.handle(KnownPacks) während ABSORBING
    public void absorbKnownPacks(KnownPacks knownPacks)
    {
        digestKnownPacks( knownPacks );
        buffered.add( knownPacks );
    }

    // Rückruf nach PlayerConfigurationEvent: die gespeicherte Antwort des Clients schicken
    public void answerKnownPacks(ServerConnection server)
    {
        if ( phase == Phase.ABSORBING && target == server )
        {
            server.getCh().write( Unpooled.wrappedBuffer( clientKnownPacksReply.clone() ) );
            answeredKnownPacks = true;
        }
    }

    // DownstreamBridge.handle(FinishConfiguration) während ABSORBING
    public void absorbFinish(FinishConfiguration finishConfiguration)
    {
        ConfigFingerprint fingerprint = configDigest;
        configDigest = null;
        SeamlessSwitch.rememberFingerprint( target.getInfo(), clientKnownPacksReply, fingerprint );
        if ( !fingerprint.sameAs( realFingerprint ) )
        {
            fallbackReason = "Registries/Tags/Packs anders als beim letzten Server: " + fingerprint.describeDifference( realFingerprint );
            realFingerprint = fingerprint;
            buffered.add( finishConfiguration );
            startFallback();
            return;
        }

        target.getCh().write( new FinishConfiguration() );
        phase = Phase.AWAIT_LOGIN;
        for ( Object entry : buffered )
        {
            if ( entry instanceof ByteBuf )
            {
                ByteBuf raw = (ByteBuf) entry;
                if ( !isPopOfAnsweredPack( raw ) )
                {
                    ByteBuf play = configToPlay( raw );
                    if ( play != null )
                    {
                        con.getCh().write( play );
                    }
                }
                raw.release();
            }
        }
        buffered.clear();
        con.sendQueuedPackets();
        SeamlessSwitch.debug( con, "Konfiguration übernommen, warte auf Login" );
    }

    /*
     * Paper schickt bei replace(true) vor dem Push ein Pop. Den Push hat der Proxy beantwortet,
     * weil der Client das Pack schon hat; das Pop darf dann nicht durch, sonst steht er ohne da.
     */
    private boolean isPopOfAnsweredPack(ByteBuf raw)
    {
        if ( answeredPacks.isEmpty() )
        {
            return false;
        }
        ByteBuf buf = raw.duplicate();
        if ( DefinedPacket.readVarInt( buf ) != Ids.CFG_RESOURCE_PACK_POP )
        {
            return false;
        }
        return !buf.readBoolean() || answeredPacks.contains( DefinedPacket.readUUID( buf ) );
    }

    // Konfig-Pakete, die es auch im Spiel gibt, mit der PLAY-Nummer weiterreichen
    private static ByteBuf configToPlay(ByteBuf raw)
    {
        ByteBuf buf = raw.duplicate();
        int id = DefinedPacket.readVarInt( buf );
        int playId;
        switch ( id )
        {
            case Ids.CFG_CUSTOM_PAYLOAD:
                playId = Ids.CUSTOM_PAYLOAD;
                break;
            case Ids.CFG_SERVER_LINKS:
                playId = Ids.SERVER_LINKS;
                break;
            case Ids.CFG_CUSTOM_REPORT_DETAILS:
                playId = Ids.CUSTOM_REPORT_DETAILS;
                break;
            case Ids.CFG_STORE_COOKIE:
                playId = Ids.STORE_COOKIE;
                break;
            case Ids.CFG_RESOURCE_PACK_POP:
                playId = Ids.RESOURCE_PACK_POP;
                break;
            case Ids.CFG_CLEAR_DIALOG:
                playId = Ids.CLEAR_DIALOG;
                break;
            default:
                return null;
        }
        ByteBuf out = SeamlessSwitch.packet( playId );
        out.writeBytes( buf );
        return out;
    }

    // Doch normal wechseln: Client in die Konfiguration schicken und alles nachreichen
    private void startFallback()
    {
        phase = Phase.FALLBACK;
        SeamlessSwitch.info( con, "normaler Wechsel: " + fallbackReason );
        if ( con.isBundling() )
        {
            con.toggleBundling();
            con.unsafe().sendPacket( new BundleDelimiter() );
        }
        con.unsafe().sendPacket( new StartConfiguration() );
        fallbackAckPending = true;
        boolean finished = false;
        for ( Object entry : buffered )
        {
            if ( entry instanceof ByteBuf )
            {
                con.getCh().write( entry );
            } else
            {
                con.unsafe().sendPacket( (DefinedPacket) entry );
                finished |= entry instanceof FinishConfiguration;
            }
        }
        buffered.clear();
        if ( finished )
        {
            con.sendQueuedPackets();
        }
    }

    // UpstreamBridge.handle(StartConfiguration): Bestätigung unseres Fallbacks schlucken
    public boolean consumeFallbackAck()
    {
        if ( fallbackAckPending )
        {
            fallbackAckPending = false;
            return true;
        }
        return false;
    }

    /*========================================================================*/
    // Login des neuen Servers

    // Echter Login (normaler Wechsel oder erster Join): Client baut alles neu auf
    public void onRealLogin(Login login)
    {
        if ( !active )
        {
            return;
        }
        if ( phase == Phase.FROZEN || phase == Phase.AWAIT_LOGIN )
        {
            phase = Phase.IDLE;
        }
        clearWorld();
        waypoints.clear();
        ghostChunks = null;
        signedChatSeen = false;
        dead = false;
        viewDistance = login.getViewDistance();
    }

    // Login beim nahtlosen Wechsel: nichts davon geht als Login an den Client
    public void onSeamlessLogin(Login login)
    {
        int clientId = con.getClientEntityId();
        con.setServerEntityId( login.getEntityId() );
        viewDistance = login.getViewDistance();

        con.getTabListHandler().onSeamlessServerChange( con.getRewriteId() );
        clearScoreboard();
        for ( UUID bossbar : con.getSentBossBars() )
        {
            con.unsafe().sendPacket( new BossBar( bossbar, 1 ) );
        }
        con.getSentBossBars().clear();

        con.unsafe().sendPacket( new EntityStatus( clientId, login.isReducedDebugInfo() ? EntityStatus.DEBUG_INFO_REDUCED : EntityStatus.DEBUG_INFO_NORMAL ) );
        con.unsafe().sendPacket( new GameState( GameState.IMMEDIATE_RESPAWN, login.isNormalRespawn() ? 0 : 1 ) );
        con.unsafe().sendPacket( new GameState( (short) 12, login.isLimitedCrafting() ? 1 : 0 ) );
        untrackWaypoints();
        // Ein offener Dialog des alten Servers hätte tote Knöpfe
        con.getCh().write( SeamlessSwitch.packet( Ids.CLEAR_DIALOG ) );
        ByteBuf camera = SeamlessSwitch.packet( Ids.SET_CAMERA );
        DefinedPacket.writeVarInt( clientId, camera );
        con.getCh().write( camera );

        if ( !Objects.equals( login.getDimension(), con.getDimension() ) )
        {
            // Andere Dimensionsart: der Client muss die Welt neu bauen (kurzer Ladebildschirm)
            con.unsafe().sendPacket( new Respawn( login.getDimension(), login.getWorldName(), login.getSeed(), login.getDifficulty(), login.getGameMode(), login.getPreviousGameMode(), login.getLevelType(), login.isDebug(), login.isFlat(),
                    (byte) 0, login.getDeathLocation(), login.getPortalCooldown(), login.getSeaLevel() ) );
            clearWorld();
            ghostChunks = null;
            phase = Phase.IDLE;
            SeamlessSwitch.debug( con, "andere Dimension, Wechsel per Respawn" );
        } else
        {
            removeOldEntities( clientId );
            removeOwnEffects( clientId );
            resetCooldowns();
            resetOwnEntityData( clientId );
            if ( openContainer >= 0 )
            {
                ByteBuf close = SeamlessSwitch.packet( Ids.CONTAINER_CLOSE );
                DefinedPacket.writeVarInt( openContainer, close );
                con.getCh().write( close );
                openContainer = -1;
            }
            con.unsafe().sendPacket( new GameState( (short) 7, 0 ) );
            con.unsafe().sendPacket( new GameState( (short) 8, 0 ) );
            con.unsafe().sendPacket( new GameState( (short) 3, login.getGameMode() ) );

            Set<Long> ghosts = new HashSet<>( chunks );
            if ( ghostChunks != null )
            {
                ghosts.addAll( ghostChunks );
            }
            ghostChunks = ghosts;
            chunks.clear();
            freeze();
        }

        con.unsafe().sendPacket( new ViewDistance( login.getViewDistance() ) );
        ByteBuf simulation = SeamlessSwitch.packet( Ids.SET_SIMULATION_DISTANCE );
        DefinedPacket.writeVarInt( login.getSimulationDistance(), simulation );
        con.getCh().write( simulation );
        con.setDimension( login.getDimension() );
        con.setGamemode( login.getGameMode() );

        // Der Client meldet seine Chat-Sitzung nur nach einem echten Login; für den neuen Server nachholen
        if ( clientChatSession != null )
        {
            target.getCh().write( Unpooled.wrappedBuffer( clientChatSession.clone() ) );
        }
        replayClientMessages();
        SeamlessSwitch.info( con, "nahtlos gewechselt nach " + target.getInfo().getName() );
    }

    /*
     * Etwa voicechat:request_secret: Simple Voice Chat fragt sein Secret nur beim Welt-Join an.
     * Der Weg über PluginMessageEvent ist nötig, weil das Voice-Plugin auf dem Proxy die
     * Anfrage sehen muss, bevor es die Antwort des Backends durchlässt.
     */
    private void replayClientMessages()
    {
        for ( Map.Entry<String, byte[]> message : replayMessages.entrySet() )
        {
            PluginMessageEvent event = new PluginMessageEvent( con, target, message.getKey(), message.getValue().clone() );
            if ( !BungeeCord.getInstance().getPluginManager().callEvent( event ).isCancelled() )
            {
                target.unsafe().sendPacket( new PluginMessage( message.getKey(), message.getValue().clone(), false ) );
                SeamlessSwitch.debug( con, "nachgereicht: " + message.getKey() );
            }
        }
    }

    private void clearScoreboard()
    {
        Scoreboard serverScoreboard = con.getServerSentScoreboard();
        for ( Objective objective : serverScoreboard.getObjectives() )
        {
            con.unsafe().sendPacket( new ScoreboardObjective( objective.getName(), Either.right( con.getChatSerializer().deserialize( objective.getValue() ) ),
                    ScoreboardObjective.HealthDisplay.fromString( objective.getType() ), (byte) 1, null ) );
        }
        for ( Score score : serverScoreboard.getScores() )
        {
            con.unsafe().sendPacket( new ScoreboardScoreReset( score.getItemName(), null ) );
        }
        for ( Team team : serverScoreboard.getTeams() )
        {
            con.unsafe().sendPacket( new net.md_5.bungee.protocol.packet.Team( team.getName() ) );
        }
        serverScoreboard.clear();
    }

    private void removeOldEntities(int clientId)
    {
        entities.remove( clientId );
        if ( !entities.isEmpty() )
        {
            ByteBuf remove = SeamlessSwitch.packet( Ids.REMOVE_ENTITIES );
            DefinedPacket.writeVarInt( entities.size(), remove );
            for ( int id : entities )
            {
                DefinedPacket.writeVarInt( id, remove );
            }
            con.getCh().write( remove );
        }
        entities.clear();
    }

    // Eigene Metadaten auf Standard: der neue Server schickt nur Werte, die davon abweichen.
    // Belegung 26.1.2: Entity 0-7, LivingEntity 8-14, Avatar 15-16, Player 17-20.
    // Leben (9), Haupthand (15) und Skin-Teile (16) bleiben, die kommen ohnehin neu.
    private void resetOwnEntityData(int clientId)
    {
        ByteBuf data = SeamlessSwitch.packet( Ids.SET_ENTITY_DATA );
        DefinedPacket.writeVarInt( clientId, data );
        metaByte( data, 0, 0 ); // Flags: Feuer, Schleichen, Unsichtbar, Leuchten ...
        metaVarInt( data, 1, 1, 300 ); // Luft
        meta( data, 2, 6 ).writeBoolean( false ); // eigener Name
        meta( data, 3, 8 ).writeBoolean( false );
        meta( data, 4, 8 ).writeBoolean( false ); // lautlos
        meta( data, 5, 8 ).writeBoolean( false ); // keine Schwerkraft
        metaVarInt( data, 6, 20, 0 ); // Pose: STANDING
        metaVarInt( data, 7, 1, 0 ); // Vereisung
        metaByte( data, 8, 0 ); // Hand benutzen, Riptide
        metaVarInt( data, 10, 17, 0 ); // Effekt-Partikel: leere Liste
        meta( data, 11, 8 ).writeBoolean( false );
        metaVarInt( data, 12, 1, 0 ); // Pfeile im Körper
        metaVarInt( data, 13, 1, 0 ); // Bienenstacheln
        meta( data, 14, 11 ).writeBoolean( false ); // Schlafplatz
        meta( data, 17, 3 ).writeFloat( 0 ); // Absorption
        metaVarInt( data, 18, 1, 0 ); // Punkte
        metaVarInt( data, 19, 19, 0 ); // Papagei links
        metaVarInt( data, 20, 19, 0 ); // Papagei rechts
        data.writeByte( 0xFF );
        con.getCh().write( data );
    }

    private static ByteBuf meta(ByteBuf data, int index, int serializer)
    {
        data.writeByte( index );
        DefinedPacket.writeVarInt( serializer, data );
        return data;
    }

    private static void metaByte(ByteBuf data, int index, int value)
    {
        meta( data, index, 0 ).writeByte( value );
    }

    private static void metaVarInt(ByteBuf data, int index, int serializer, int value)
    {
        DefinedPacket.writeVarInt( value, meta( data, index, serializer ) );
    }

    private void removeOwnEffects(int clientId)
    {
        for ( int effect : ownEffects )
        {
            ByteBuf remove = SeamlessSwitch.packet( Ids.REMOVE_MOB_EFFECT );
            DefinedPacket.writeVarInt( clientId, remove );
            DefinedPacket.writeVarInt( effect, remove );
            con.getCh().write( remove );
        }
        ownEffects.clear();
    }

    /*========================================================================*/
    // Schweben, bis der Ankunfts-Chunk da ist

    private void freeze()
    {
        phase = Phase.FROZEN;
        arrivalKnown = false;
        heldAbilities = null;
        heldGameMode = null;
        ByteBuf abilities = SeamlessSwitch.packet( Ids.PLAYER_ABILITIES );
        abilities.writeByte( 0x02 | 0x04 );
        abilities.writeFloat( 0.0F );
        abilities.writeFloat( 0.1F );
        con.getCh().write( abilities );

        final int frozenGeneration = generation;
        con.getCh().getHandle().eventLoop().schedule( () ->
        {
            if ( phase == Phase.FROZEN && generation == frozenGeneration )
            {
                SeamlessSwitch.info( con, "Ankunfts-Chunk kam nicht rechtzeitig, gebe frei" );
                release( true );
            }
        }, SeamlessSwitch.FREEZE_TIMEOUT_MS, TimeUnit.MILLISECONDS );
    }

    // announceLoaded = dem Server melden, dass der Client die Welt hat (nicht nach einem Respawn, das meldet der Client selbst)
    private void release(boolean announceLoaded)
    {
        if ( phase != Phase.FROZEN )
        {
            return;
        }
        phase = Phase.IDLE;
        // Zuerst der Spielmodus: der setzt beim Client die Fähigkeiten, die echten kommen danach
        if ( heldGameMode != null )
        {
            con.getCh().write( Unpooled.wrappedBuffer( heldGameMode ) );
            heldGameMode = null;
        }
        ByteBuf abilities;
        if ( heldAbilities != null )
        {
            abilities = Unpooled.wrappedBuffer( heldAbilities );
        } else
        {
            abilities = SeamlessSwitch.packet( Ids.PLAYER_ABILITIES );
            abilities.writeByte( 0 );
            abilities.writeFloat( 0.05F );
            abilities.writeFloat( 0.1F );
        }
        heldAbilities = null;
        con.getCh().write( abilities );

        // Sonst wartet der Server 60 Ticks, bevor er Bewegungen annimmt
        if ( announceLoaded && target != null && !target.isObsolete() )
        {
            target.getCh().write( SeamlessSwitch.packet( Ids.SB_PLAYER_LOADED ) );
        }

        final int releasedGeneration = generation;
        con.getCh().getHandle().eventLoop().schedule( () ->
        {
            cleanupGhosts( releasedGeneration );
        }, SeamlessSwitch.GHOST_CLEANUP_DELAY_MS, TimeUnit.MILLISECONDS );
        SeamlessSwitch.debug( con, "freigegeben" );
    }

    // Chunks des alten Servers, die der neue nicht ersetzt hat, beim Client entfernen
    private void cleanupGhosts(int releasedGeneration)
    {
        if ( generation != releasedGeneration || ghostChunks == null || con.getCh().isClosed() )
        {
            return;
        }
        int range = viewDistance + 3;
        int sent = 0;
        for ( long key : ghostChunks )
        {
            if ( !centerKnown || ( Math.abs( SeamlessSwitch.chunkX( key ) - centerX ) <= range && Math.abs( SeamlessSwitch.chunkZ( key ) - centerZ ) <= range ) )
            {
                ByteBuf forget = SeamlessSwitch.packet( Ids.FORGET_LEVEL_CHUNK );
                forget.writeLong( key );
                con.getCh().write( forget );
                sent++;
            }
        }
        ghostChunks = null;
        SeamlessSwitch.debug( con, sent + " alte Chunks entfernt" );
    }

    /*========================================================================*/
    // Laufende Pakete Server -> Client

    // Für Pakete in der Konfigurationsphase eines echten Wechsels
    public void observeConfig(PacketWrapper packet)
    {
        ByteBuf buf = packet.buf.duplicate();
        int id = DefinedPacket.readVarInt( buf );
        switch ( id )
        {
            case Ids.CFG_REGISTRY_DATA:
            case Ids.CFG_UPDATE_TAGS:
            case Ids.CFG_UPDATE_ENABLED_FEATURES:
                digest( packet.buf );
                break;
            case Ids.CFG_RESOURCE_PACK_PUSH:
                UUID uuid = DefinedPacket.readUUID( buf );
                DefinedPacket.readString( buf );
                pushedPacks.put( uuid, DefinedPacket.readString( buf ) );
                break;
            default:
                break;
        }
    }

    public void observeKnownPacks(KnownPacks knownPacks)
    {
        if ( active )
        {
            digestKnownPacks( knownPacks );
        }
    }

    public void onRealFinishConfiguration(ServerConnection server)
    {
        if ( active && configDigest != null )
        {
            realFingerprint = configDigest;
            configDigest = null;
            SeamlessSwitch.rememberFingerprint( server.getInfo(), clientKnownPacksReply, realFingerprint );
        }
    }

    // UpstreamBridge.handle(PluginMessage): Nachrichten merken, die der Client nur beim Welt-Join schickt
    public void onClientPluginMessage(String tag, byte[] data)
    {
        if ( active && SeamlessSwitch.isReplayChannel( tag ) )
        {
            replayMessages.put( tag, data.clone() );
        }
    }

    // PLAY-Pakete Server -> Client; null = nicht (mehr) weiterleiten
    public PacketWrapper onClientbound(PacketWrapper packet)
    {
        if ( packet.protocol == Protocol.CONFIGURATION )
        {
            observeConfig( packet );
            return packet;
        }
        if ( packet.protocol != Protocol.GAME )
        {
            return packet;
        }

        PacketWrapper wrapper = packet;
        ByteBuf rewritten = EntityIdRewriter.rewriteClientbound( packet.buf, con.getServerEntityId(), con.getClientEntityId(), con.getPendingConnection().getVersion() );
        if ( rewritten != null )
        {
            wrapper = new PacketWrapper( packet.packet, rewritten, packet.protocol );
        }
        // Raketen-Boost: die Rakete merkt sich in ihren Metadaten den Spieler, an dem sie hängt
        if ( !fireworks.isEmpty() && con.getServerEntityId() != con.getClientEntityId() && isFireworkData( wrapper.buf ) )
        {
            ByteBuf retargeted = EntityIdRewriter.rewriteFireworkTarget( wrapper.buf, con.getServerEntityId(), con.getClientEntityId() );
            if ( retargeted != null )
            {
                if ( wrapper != packet )
                {
                    wrapper.trySingleRelease();
                }
                wrapper = new PacketWrapper( packet.packet, retargeted, packet.protocol );
            }
        }

        try
        {
            if ( track( wrapper ) )
            {
                return wrapper;
            }
        } catch ( RuntimeException ex )
        {
            SeamlessSwitch.debug( con, "Paket nicht lesbar: " + ex );
            return wrapper;
        }
        if ( wrapper != packet )
        {
            wrapper.trySingleRelease();
        }
        return null;
    }

    private boolean isFireworkData(ByteBuf packet)
    {
        if ( SeamlessSwitch.peekPacketId( packet ) != Ids.SET_ENTITY_DATA )
        {
            return false;
        }
        ByteBuf buf = packet.duplicate();
        DefinedPacket.readVarInt( buf );
        return fireworks.contains( DefinedPacket.readVarInt( buf ) );
    }

    // Nur diese PLAY-Pakete werden mitgelesen, der Rest geht ohne Umweg durch
    private static final boolean[] TRACKED = new boolean[ 256 ];

    static
    {
        int[] ids =
        {
            Ids.ADD_ENTITY, Ids.REMOVE_ENTITIES, Ids.LEVEL_CHUNK_WITH_LIGHT, Ids.FORGET_LEVEL_CHUNK, Ids.SET_CHUNK_CACHE_CENTER,
            Ids.UPDATE_MOB_EFFECT, Ids.REMOVE_MOB_EFFECT, Ids.OPEN_SCREEN, Ids.CONTAINER_CLOSE, Ids.PLAYER_CHAT,
            Ids.RESOURCE_PACK_PUSH, Ids.RESOURCE_PACK_POP, Ids.RESPAWN, Ids.START_CONFIGURATION, Ids.PLAYER_ABILITIES,
            Ids.PLAYER_POSITION, Ids.COOLDOWN, Ids.WAYPOINT, Ids.SET_HEALTH, Ids.PLAYER_COMBAT_KILL, Ids.GAME_EVENT
        };
        for ( int id : ids )
        {
            TRACKED[id] = true;
        }
    }

    // false = Paket nicht weiterleiten (wurde gehalten oder schon geschickt)
    private boolean track(PacketWrapper packet)
    {
        int id = SeamlessSwitch.peekPacketId( packet.buf );
        if ( id < 0 || id >= TRACKED.length || !TRACKED[id] )
        {
            return true;
        }
        ByteBuf buf = packet.buf.duplicate();
        DefinedPacket.readVarInt( buf );
        switch ( id )
        {
            case Ids.ADD_ENTITY:
                int added = DefinedPacket.readVarInt( buf );
                entities.add( added );
                buf.skipBytes( 16 );
                if ( DefinedPacket.readVarInt( buf ) == Ids.entityType( Ids.FIREWORK_ROCKET_TYPE, con.getPendingConnection().getVersion() ) )
                {
                    fireworks.add( added );
                }
                return true;
            case Ids.REMOVE_ENTITIES:
                int count = DefinedPacket.readVarInt( buf );
                for ( int i = 0; i < count; i++ )
                {
                    int removed = DefinedPacket.readVarInt( buf );
                    entities.remove( removed );
                    fireworks.remove( removed );
                }
                return true;
            case Ids.GAME_EVENT:
                // Spielmodus im Schweben setzt beim Client flying=false: bis zur Freigabe halten
                if ( phase == Phase.FROZEN && buf.readUnsignedByte() == 3 )
                {
                    heldGameMode = copy( packet.buf );
                    return false;
                }
                return true;
            case Ids.LEVEL_CHUNK_WITH_LIGHT:
                return onChunk( packet, SeamlessSwitch.chunkKey( buf.readInt(), buf.readInt() ) );
            case Ids.FORGET_LEVEL_CHUNK:
                long forgotten = buf.readLong();
                chunks.remove( forgotten );
                if ( ghostChunks != null )
                {
                    ghostChunks.remove( forgotten );
                }
                return true;
            case Ids.SET_CHUNK_CACHE_CENTER:
                centerX = DefinedPacket.readVarInt( buf );
                centerZ = DefinedPacket.readVarInt( buf );
                centerKnown = true;
                return true;
            case Ids.UPDATE_MOB_EFFECT:
                if ( DefinedPacket.readVarInt( buf ) == con.getClientEntityId() )
                {
                    ownEffects.add( DefinedPacket.readVarInt( buf ) );
                }
                return true;
            case Ids.REMOVE_MOB_EFFECT:
                if ( DefinedPacket.readVarInt( buf ) == con.getClientEntityId() )
                {
                    ownEffects.remove( DefinedPacket.readVarInt( buf ) );
                }
                return true;
            case Ids.OPEN_SCREEN:
                openContainer = DefinedPacket.readVarInt( buf );
                return true;
            case Ids.CONTAINER_CLOSE:
                openContainer = -1;
                return true;
            case Ids.PLAYER_CHAT:
                DefinedPacket.readVarInt( buf );
                buf.skipBytes( 16 );
                DefinedPacket.readVarInt( buf );
                if ( buf.readBoolean() )
                {
                    signedChatSeen = true;
                }
                return true;
            case Ids.RESOURCE_PACK_PUSH:
                UUID pushed = DefinedPacket.readUUID( buf );
                DefinedPacket.readString( buf );
                pushedPacks.put( pushed, DefinedPacket.readString( buf ) );
                return true;
            case Ids.RESOURCE_PACK_POP:
                if ( buf.readBoolean() )
                {
                    forgetPack( DefinedPacket.readUUID( buf ) );
                } else
                {
                    loadedPacks.clear();
                }
                return true;
            case Ids.RESPAWN:
            case Ids.START_CONFIGURATION:
                // Der Client baut seine Welt neu auf
                clearWorld();
                dead = false;
                if ( phase == Phase.FROZEN )
                {
                    // Nach einem Respawn meldet der Client das Laden selbst
                    ghostChunks = null;
                    release( false );
                }
                return true;
            case Ids.PLAYER_ABILITIES:
                if ( phase == Phase.FROZEN )
                {
                    heldAbilities = copy( packet.buf );
                    return false;
                }
                return true;
            case Ids.PLAYER_POSITION:
                return onPlayerPosition( packet, buf );
            case Ids.SET_HEALTH:
                dead = buf.readFloat() <= 0;
                return true;
            case Ids.PLAYER_COMBAT_KILL:
                if ( DefinedPacket.readVarInt( buf ) == con.getClientEntityId() )
                {
                    dead = true;
                }
                return true;
            case Ids.COOLDOWN:
                String group = DefinedPacket.readString( buf );
                if ( DefinedPacket.readVarInt( buf ) > 0 )
                {
                    cooldowns.add( group );
                } else
                {
                    cooldowns.remove( group );
                }
                return true;
            case Ids.WAYPOINT:
                int operation = DefinedPacket.readVarInt( buf );
                String waypoint = buf.readBoolean() ? DefinedPacket.readUUID( buf ).toString() : "s:" + DefinedPacket.readString( buf );
                if ( operation == 1 )
                {
                    waypoints.remove( waypoint );
                } else
                {
                    waypoints.put( waypoint, copy( packet.buf ) );
                }
                return true;
            default:
                return true;
        }
    }

    // Wegpunkte der Ortungsleiste gehören zur Verbindung, nicht zur Welt: einzeln abmelden
    private void untrackWaypoints()
    {
        for ( byte[] raw : waypoints.values() )
        {
            ByteBuf old = Unpooled.wrappedBuffer( raw );
            DefinedPacket.readVarInt( old );
            DefinedPacket.readVarInt( old );
            ByteBuf untrack = SeamlessSwitch.packet( Ids.WAYPOINT );
            DefinedPacket.writeVarInt( 1, untrack );
            untrack.writeBytes( old );
            con.getCh().write( untrack );
        }
        waypoints.clear();
    }

    private void resetCooldowns()
    {
        for ( String group : cooldowns )
        {
            ByteBuf reset = SeamlessSwitch.packet( Ids.COOLDOWN );
            DefinedPacket.writeString( group, reset );
            DefinedPacket.writeVarInt( 0, reset );
            con.getCh().write( reset );
        }
        cooldowns.clear();
    }

    private boolean onChunk(PacketWrapper packet, long key)
    {
        chunks.add( key );
        if ( ghostChunks != null )
        {
            ghostChunks.remove( key );
        }
        if ( phase == Phase.FROZEN && arrivalKnown && key == arrivalChunk )
        {
            con.sendPacket( packet );
            release( true );
            return false;
        }
        return true;
    }

    private boolean onPlayerPosition(PacketWrapper packet, ByteBuf buf)
    {
        // Jeder absolute Teleport im Schweben ersetzt das Ziel: Plugins teleportieren beim Join oft
        // ein zweites Mal (Spawn), freigegeben wird erst, wenn der Chunk am letzten Ziel da ist
        if ( phase != Phase.FROZEN )
        {
            return true;
        }
        DefinedPacket.readVarInt( buf );
        double x = buf.readDouble();
        buf.readDouble();
        double z = buf.readDouble();
        buf.skipBytes( 24 + 8 );
        int relatives = buf.readInt();
        if ( ( relatives & 0b101 ) != 0 )
        {
            // Relative Teleports kennen wir nicht genau: Freigabe per Zeitlimit
            return true;
        }
        arrivalKnown = true;
        arrivalChunk = SeamlessSwitch.chunkKey( (int) Math.floor( x ) >> 4, (int) Math.floor( z ) >> 4 );
        if ( chunks.contains( arrivalChunk ) )
        {
            con.sendPacket( packet );
            release( true );
            return false;
        }
        return true;
    }

    private void clearWorld()
    {
        entities.clear();
        chunks.clear();
        ownEffects.clear();
        cooldowns.clear();
        fireworks.clear();
        openContainer = -1;
    }

    /*========================================================================*/
    // Pakete Client -> Server

    // null = nicht weiterleiten
    public PacketWrapper onServerbound(PacketWrapper packet)
    {
        ByteBuf buf = packet.buf.duplicate();
        try
        {
            int id = DefinedPacket.readVarInt( buf );
            if ( packet.protocol == Protocol.CONFIGURATION )
            {
                return onServerboundConfig( packet, buf, id );
            }
            if ( packet.protocol != Protocol.GAME )
            {
                return packet;
            }
            if ( phase == Phase.FROZEN && id == Ids.SB_PLAYER_ABILITIES )
            {
                // Der Schwebe-Flug ist nur geliehen; ein "ich fliege" vom Client wäre für das Anticheat ein Flug-Hack
                return null;
            }
            if ( id == Ids.SB_CHAT_SESSION_UPDATE )
            {
                clientChatSession = copy( packet.buf );
            } else if ( id == Ids.SB_RESOURCE_PACK )
            {
                packStatus( DefinedPacket.readUUID( buf ), DefinedPacket.readVarInt( buf ) );
            }
        } catch ( RuntimeException ex )
        {
            return packet;
        }

        ByteBuf rewritten = EntityIdRewriter.rewriteServerbound( packet.buf, con.getClientEntityId(), con.getServerEntityId() );
        if ( rewritten != null )
        {
            return new PacketWrapper( packet.packet, rewritten, packet.protocol );
        }
        return packet;
    }

    private PacketWrapper onServerboundConfig(PacketWrapper packet, ByteBuf buf, int id)
    {
        switch ( id )
        {
            case Ids.CFG_SB_SELECT_KNOWN_PACKS:
                if ( phase == Phase.FALLBACK && answeredKnownPacks )
                {
                    // Hat das Backend schon von uns bekommen
                    answeredKnownPacks = false;
                    return null;
                }
                clientKnownPacksReply = copy( packet.buf );
                return packet;
            case Ids.CFG_SB_RESOURCE_PACK:
                UUID uuid = DefinedPacket.readUUID( buf );
                int action = DefinedPacket.readVarInt( buf );
                if ( phase == Phase.FALLBACK && answeredPacks.contains( uuid ) )
                {
                    if ( isTerminalPackAction( action ) )
                    {
                        answeredPacks.remove( uuid );
                    }
                    return null;
                }
                packStatus( uuid, action );
                return packet;
            case Ids.CFG_SB_FINISH:
                if ( phase == Phase.FALLBACK )
                {
                    phase = Phase.IDLE;
                }
                return packet;
            default:
                return packet;
        }
    }

    /*========================================================================*/
    // Abbruch

    // Backend weg oder gekickt: laufenden Versuch verwerfen
    public void reset(String why)
    {
        if ( !active )
        {
            return;
        }
        if ( phase != Phase.IDLE )
        {
            SeamlessSwitch.debug( con, "Versuch abgebrochen: " + why );
        }
        if ( phase == Phase.FROZEN )
        {
            release( false );
        }
        phase = Phase.IDLE;
        target = null;
        fallbackAckPending = false;
        configDigest = null;
        releaseBuffered();
    }

    public void onServerGone(ServerConnection server)
    {
        if ( target == server && phase != Phase.IDLE )
        {
            reset( "Server getrennt" );
        }
    }

    /*========================================================================*/
    // Hilfen

    private static boolean isTerminalPackAction(int action)
    {
        return action != 3 && action != 4;
    }

    private void packStatus(UUID uuid, int action)
    {
        if ( action == 0 )
        {
            String hash = pushedPacks.get( uuid );
            if ( hash != null )
            {
                loadedPacks.add( packKey( uuid, hash ) );
            }
        } else if ( isTerminalPackAction( action ) )
        {
            forgetPack( uuid );
        }
    }

    private void forgetPack(UUID uuid)
    {
        String prefix = uuid + "|";
        loadedPacks.removeIf( key -> key.startsWith( prefix ) );
    }

    private static String packKey(UUID uuid, String hash)
    {
        return uuid + "|" + hash;
    }

    private void releaseBuffered()
    {
        for ( Object entry : buffered )
        {
            if ( entry instanceof ByteBuf )
            {
                ( (ByteBuf) entry ).release();
            }
        }
        buffered.clear();
    }

    private void digest(ByteBuf buf)
    {
        if ( configDigest == null )
        {
            configDigest = new ConfigFingerprint();
        }
        configDigest.addPacket( buf );
    }

    private void digestKnownPacks(KnownPacks knownPacks)
    {
        if ( configDigest == null )
        {
            configDigest = new ConfigFingerprint();
        }
        StringBuilder text = new StringBuilder( "packs:" );
        for ( KnownPacks.KnownPack pack : knownPacks.getKnownPacks() )
        {
            text.append( pack.getNamespace() ).append( ':' ).append( pack.getId() ).append( ':' ).append( pack.getVersion() ).append( ';' );
        }
        configDigest.addKnownPacks( text.toString() );
    }

    private static byte[] copy(ByteBuf buf)
    {
        byte[] bytes = new byte[ buf.readableBytes() ];
        buf.getBytes( buf.readerIndex(), bytes );
        return bytes;
    }
}
