package net.md_5.bungee.seamless;

import static org.junit.jupiter.api.Assertions.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.protocol.DefinedPacket;
import net.md_5.bungee.protocol.ProtocolConstants;
import net.md_5.bungee.seamless.SeamlessSwitch.Ids;
import org.junit.jupiter.api.Test;

public class ConfigFingerprintTest
{

    // update_tags mit einer Registry; tags = abwechselnd Name und ID
    private static ByteBuf tags(String registry, Object... tags)
    {
        ByteBuf buf = Unpooled.buffer();
        DefinedPacket.writeVarInt( Ids.CFG_UPDATE_TAGS, buf );
        DefinedPacket.writeVarInt( 1, buf );
        DefinedPacket.writeString( registry, buf );
        DefinedPacket.writeVarInt( tags.length / 2, buf );
        for ( int i = 0; i < tags.length; i += 2 )
        {
            DefinedPacket.writeString( (String) tags[i], buf );
            DefinedPacket.writeVarInt( 1, buf );
            DefinedPacket.writeVarInt( (Integer) tags[i + 1], buf );
        }
        return buf;
    }

    private static void nbtName(ByteBuf buf, String name)
    {
        byte[] bytes = name.getBytes( StandardCharsets.UTF_8 );
        buf.writeShort( bytes.length );
        buf.writeBytes( bytes );
    }

    // Netzwerk-NBT: Compound mit Int "fog" und String "name", wahlweise in umgekehrter Folge;
    // dazu eine Liste, deren Reihenfolge zählen muss
    private static void biomeNbt(ByteBuf buf, int fog, boolean reversed, int... list)
    {
        buf.writeByte( 10 );
        if ( reversed )
        {
            buf.writeByte( 8 );
            nbtName( buf, "name" );
            nbtName( buf, "herbst" );
        }
        buf.writeByte( 3 );
        nbtName( buf, "fog" );
        buf.writeInt( fog );
        buf.writeByte( 9 );
        nbtName( buf, "list" );
        buf.writeByte( 3 );
        buf.writeInt( list.length );
        for ( int value : list )
        {
            buf.writeInt( value );
        }
        if ( !reversed )
        {
            buf.writeByte( 8 );
            nbtName( buf, "name" );
            nbtName( buf, "herbst" );
        }
        buf.writeByte( 0 );
    }

    // registry_data minecraft:worldgen/biome; Einträge: plains (ohne Daten) und tryus:herbst (mit NBT)
    private static ByteBuf biomes(boolean herbstFirst, int fog, boolean reversed, int... list)
    {
        ByteBuf buf = Unpooled.buffer();
        DefinedPacket.writeVarInt( Ids.CFG_REGISTRY_DATA, buf );
        DefinedPacket.writeString( "minecraft:worldgen/biome", buf );
        DefinedPacket.writeVarInt( 2, buf );
        for ( int i = 0; i < 2; i++ )
        {
            if ( ( i == 0 ) == herbstFirst )
            {
                DefinedPacket.writeString( "tryus:herbst", buf );
                buf.writeBoolean( true );
                biomeNbt( buf, fog, reversed, list );
            } else
            {
                DefinedPacket.writeString( "minecraft:plains", buf );
                buf.writeBoolean( false );
            }
        }
        return buf;
    }

    @Test
    public void tagOrderDoesNotMatter()
    {
        ConfigFingerprint a = new ConfigFingerprint();
        a.addPacket( tags( "minecraft:block", "minecraft:logs", 5, "minecraft:leaves", 7 ) );
        ConfigFingerprint b = new ConfigFingerprint();
        b.addPacket( tags( "minecraft:block", "minecraft:leaves", 7, "minecraft:logs", 5 ) );
        assertTrue( a.sameAs( b ) );
        assertEquals( "keine Teile verschieden", a.describeDifference( b ) );
    }

    @Test
    public void compoundKeyOrderDoesNotMatter()
    {
        ConfigFingerprint a = new ConfigFingerprint();
        a.addPacket( biomes( false, 100, false, 1, 2 ) );
        ConfigFingerprint b = new ConfigFingerprint();
        b.addPacket( biomes( false, 100, true, 1, 2 ) );
        assertTrue( a.sameAs( b ) );
    }

    @Test
    public void listOrderMatters()
    {
        ConfigFingerprint a = new ConfigFingerprint();
        a.addPacket( biomes( false, 100, false, 1, 2 ) );
        ConfigFingerprint b = new ConfigFingerprint();
        b.addPacket( biomes( false, 100, false, 2, 1 ) );
        assertFalse( a.sameAs( b ) );
        assertEquals( "registry minecraft:worldgen/biome (tryus:herbst)", a.describeDifference( b ) );
    }

    @Test
    public void entryOrderMatters()
    {
        // Die Reihenfolge legt die Biom-IDs in den Chunks fest
        ConfigFingerprint a = new ConfigFingerprint();
        a.addPacket( biomes( false, 100, false ) );
        ConfigFingerprint b = new ConfigFingerprint();
        b.addPacket( biomes( true, 100, false ) );
        assertFalse( a.sameAs( b ) );
        assertEquals( "registry minecraft:worldgen/biome (Reihenfolge der Einträge anders)", a.describeDifference( b ) );
    }

    @Test
    public void differentContentIsNamed()
    {
        ConfigFingerprint a = new ConfigFingerprint();
        a.addPacket( biomes( false, 100, false ) );
        ConfigFingerprint b = new ConfigFingerprint();
        b.addPacket( biomes( false, 200, true ) );
        assertFalse( a.sameAs( b ) );
        assertEquals( "registry minecraft:worldgen/biome (tryus:herbst)", a.describeDifference( b ) );
    }

    @Test
    public void unreadableRegistryFallsBackToBytes()
    {
        ByteBuf broken = Unpooled.buffer();
        DefinedPacket.writeVarInt( Ids.CFG_REGISTRY_DATA, broken );
        DefinedPacket.writeString( "minecraft:worldgen/biome", broken );
        DefinedPacket.writeVarInt( 5, broken ); // mehr Einträge angekündigt als da sind
        ConfigFingerprint a = new ConfigFingerprint();
        a.addPacket( broken );
        ConfigFingerprint b = new ConfigFingerprint();
        b.addPacket( broken );
        assertTrue( a.sameAs( b ) );
        assertEquals( "keine Teile verschieden", a.describeDifference( b ) );
    }

    @Test
    public void differentTagsAreNamed()
    {
        ConfigFingerprint a = new ConfigFingerprint();
        a.addPacket( tags( "minecraft:block", "minecraft:logs", 5, "minecraft:leaves", 7 ) );
        ConfigFingerprint b = new ConfigFingerprint();
        b.addPacket( tags( "minecraft:block", "minecraft:mineable", 3, "minecraft:leaves", 8, "minecraft:logs", 5 ) );
        assertFalse( a.sameAs( b ) );
        assertEquals( "tags minecraft:block (minecraft:leaves, minecraft:mineable fehlt hier)", a.describeDifference( b ) );
        assertEquals( "tags minecraft:block (minecraft:leaves, minecraft:mineable nur hier)", b.describeDifference( a ) );
    }

    @Test
    public void fingerprintsAreKeptPerProtocol()
    {
        // Gleiche Pack-Antwort, andere Client-Fassung: ViaVersion schickt dann andere Registries
        ServerInfo server = (ServerInfo) Proxy.newProxyInstance( ServerInfo.class.getClassLoader(), new Class<?>[]
        {
            ServerInfo.class
        }, (proxy, method, args) -> null );
        byte[] reply = new byte[]
        {
            1, 2, 3
        };
        ConfigFingerprint fingerprint = new ConfigFingerprint();
        SeamlessSwitch.rememberFingerprint( server, ProtocolConstants.MINECRAFT_26_2, reply, fingerprint );
        assertSame( fingerprint, SeamlessSwitch.knownFingerprint( server, ProtocolConstants.MINECRAFT_26_2, reply ) );
        assertNull( SeamlessSwitch.knownFingerprint( server, ProtocolConstants.MINECRAFT_26_1, reply ) );
    }

    @Test
    public void missingPartIsNamed()
    {
        ConfigFingerprint a = new ConfigFingerprint();
        a.addKnownPacks( "packs:minecraft:core:26.1.2;" );
        ConfigFingerprint b = new ConfigFingerprint();
        assertFalse( a.sameAs( b ) );
        assertEquals( "knownpacks (nur hier)", a.describeDifference( b ) );
        assertFalse( a.sameAs( null ) );
    }
}
