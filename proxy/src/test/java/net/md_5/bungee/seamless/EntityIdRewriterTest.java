package net.md_5.bungee.seamless;

import static org.junit.jupiter.api.Assertions.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.UUID;
import net.md_5.bungee.protocol.DefinedPacket;
import net.md_5.bungee.protocol.ProtocolConstants;
import net.md_5.bungee.seamless.SeamlessSwitch.Ids;
import org.junit.jupiter.api.Test;

public class EntityIdRewriterTest
{

    private static final int CLIENT = 5;
    private static final int SERVER = 300000;
    private static final int P = ProtocolConstants.MINECRAFT_26_1;
    private static final int P26X2 = ProtocolConstants.MINECRAFT_26_2;

    private static ByteBuf packet(int id)
    {
        ByteBuf buf = Unpooled.buffer();
        DefinedPacket.writeVarInt( id, buf );
        return buf;
    }

    @Test
    public void entityDataChangesVarIntLength()
    {
        ByteBuf in = packet( Ids.SET_ENTITY_DATA );
        DefinedPacket.writeVarInt( SERVER, in );
        in.writeByte( 0xFF );

        ByteBuf out = EntityIdRewriter.rewriteClientbound( in, SERVER, CLIENT, P );
        assertNotNull( out );
        assertEquals( Ids.SET_ENTITY_DATA, DefinedPacket.readVarInt( out ) );
        assertEquals( CLIENT, DefinedPacket.readVarInt( out ) );
        assertEquals( 0xFF, out.readUnsignedByte() );
        assertFalse( out.isReadable() );
    }

    @Test
    public void otherEntitiesStayUntouched()
    {
        ByteBuf in = packet( Ids.SET_ENTITY_MOTION );
        DefinedPacket.writeVarInt( 1234, in );
        in.writeByte( 0 );
        assertNull( EntityIdRewriter.rewriteClientbound( in, SERVER, CLIENT, P ) );
    }

    @Test
    public void collisionIsSwapped()
    {
        // Ein Wesen auf dem neuen Server trägt zufällig die alte ID des Spielers
        ByteBuf in = packet( Ids.ROTATE_HEAD );
        DefinedPacket.writeVarInt( CLIENT, in );
        in.writeByte( 7 );

        ByteBuf out = EntityIdRewriter.rewriteClientbound( in, SERVER, CLIENT, P );
        DefinedPacket.readVarInt( out );
        assertEquals( SERVER, DefinedPacket.readVarInt( out ) );
        assertEquals( 7, out.readByte() );
    }

    @Test
    public void entityEventUsesFixedInt()
    {
        ByteBuf in = packet( Ids.ENTITY_EVENT );
        in.writeInt( SERVER );
        in.writeByte( 24 );

        ByteBuf out = EntityIdRewriter.rewriteClientbound( in, SERVER, CLIENT, P );
        DefinedPacket.readVarInt( out );
        assertEquals( CLIENT, out.readInt() );
        assertEquals( 24, out.readByte() );
    }

    @Test
    public void damageEventSourcesArePlusOne()
    {
        ByteBuf in = packet( Ids.DAMAGE_EVENT );
        DefinedPacket.writeVarInt( 42, in ); // getroffenes Wesen
        DefinedPacket.writeVarInt( 3, in ); // Schadensart
        DefinedPacket.writeVarInt( SERVER + 1, in ); // Verursacher = Spieler
        DefinedPacket.writeVarInt( 0, in ); // kein direkter Verursacher
        in.writeBoolean( false );

        ByteBuf out = EntityIdRewriter.rewriteClientbound( in, SERVER, CLIENT, P );
        DefinedPacket.readVarInt( out );
        assertEquals( 42, DefinedPacket.readVarInt( out ) );
        assertEquals( 3, DefinedPacket.readVarInt( out ) );
        assertEquals( CLIENT + 1, DefinedPacket.readVarInt( out ) );
        assertEquals( 0, DefinedPacket.readVarInt( out ) );
        assertFalse( out.readBoolean() );
    }

    @Test
    public void projectileOwnerInAddEntity()
    {
        ByteBuf in = packet( Ids.ADD_ENTITY );
        DefinedPacket.writeVarInt( 777, in );
        DefinedPacket.writeUUID( UUID.randomUUID(), in );
        DefinedPacket.writeVarInt( 156, in ); // fishing_bobber
        in.writeDouble( 1 );
        in.writeDouble( 2 );
        in.writeDouble( 3 );
        in.writeByte( 0 ); // Bewegung = Nullvektor
        in.writeByte( 1 );
        in.writeByte( 2 );
        in.writeByte( 3 );
        DefinedPacket.writeVarInt( SERVER, in ); // Besitzer

        ByteBuf out = EntityIdRewriter.rewriteClientbound( in, SERVER, CLIENT, P );
        assertNotNull( out );
        DefinedPacket.readVarInt( out );
        assertEquals( 777, DefinedPacket.readVarInt( out ) );
        out.skipBytes( 16 );
        assertEquals( 156, DefinedPacket.readVarInt( out ) );
        out.skipBytes( 24 + 1 + 3 );
        assertEquals( CLIENT, DefinedPacket.readVarInt( out ) );
        assertFalse( out.isReadable() );
    }

    private static ByteBuf addEntityWithOwner(int type, int owner)
    {
        ByteBuf in = packet( Ids.ADD_ENTITY );
        DefinedPacket.writeVarInt( 777, in );
        DefinedPacket.writeUUID( UUID.randomUUID(), in );
        DefinedPacket.writeVarInt( type, in );
        in.writeDouble( 1 );
        in.writeDouble( 2 );
        in.writeDouble( 3 );
        in.writeByte( 0 ); // Bewegung = Nullvektor
        in.writeByte( 1 );
        in.writeByte( 2 );
        in.writeByte( 3 );
        DefinedPacket.writeVarInt( owner, in );
        return in;
    }

    @Test
    public void entityTypesShiftIn26x2()
    {
        assertEquals( 53, Ids.entityType( 53, P26X2 ) ); // firework_rocket bleibt
        assertEquals( 130, Ids.entityType( 130, P26X2 ) );
        assertEquals( 132, Ids.entityType( 131, P26X2 ) ); // ab sulfur_cube eins weiter
        assertEquals( 157, Ids.entityType( 156, P26X2 ) ); // fishing_bobber
        assertEquals( 156, Ids.entityType( 156, P ) );
    }

    @Test
    public void projectileOwnerIn26x2()
    {
        // fishing_bobber ist in 26.2 Typ 157
        ByteBuf out = EntityIdRewriter.rewriteClientbound( addEntityWithOwner( 157, SERVER ), SERVER, CLIENT, P26X2 );
        assertNotNull( out );
        DefinedPacket.readVarInt( out );
        assertEquals( 777, DefinedPacket.readVarInt( out ) );
        out.skipBytes( 16 );
        assertEquals( 157, DefinedPacket.readVarInt( out ) );
        out.skipBytes( 24 + 1 + 3 );
        assertEquals( CLIENT, DefinedPacket.readVarInt( out ) );
        assertFalse( out.isReadable() );
    }

    @Test
    public void playerTypeIsNoOwnerIn26x2()
    {
        // 156 ist in 26.2 der Spieler: dessen "data" ist keine Besitzer-ID
        assertNull( EntityIdRewriter.rewriteClientbound( addEntityWithOwner( 156, SERVER ), SERVER, CLIENT, P26X2 ) );
    }

    @Test
    public void passengersList()
    {
        ByteBuf in = packet( Ids.SET_PASSENGERS );
        DefinedPacket.writeVarInt( 99, in );
        DefinedPacket.writeVarInt( 2, in );
        DefinedPacket.writeVarInt( SERVER, in );
        DefinedPacket.writeVarInt( 100, in );

        ByteBuf out = EntityIdRewriter.rewriteClientbound( in, SERVER, CLIENT, P );
        DefinedPacket.readVarInt( out );
        assertEquals( 99, DefinedPacket.readVarInt( out ) );
        assertEquals( 2, DefinedPacket.readVarInt( out ) );
        assertEquals( CLIENT, DefinedPacket.readVarInt( out ) );
        assertEquals( 100, DefinedPacket.readVarInt( out ) );
    }

    @Test
    public void serverboundPlayerCommand()
    {
        ByteBuf in = packet( Ids.SB_PLAYER_COMMAND );
        DefinedPacket.writeVarInt( CLIENT, in );
        DefinedPacket.writeVarInt( 1, in );
        DefinedPacket.writeVarInt( 0, in );

        ByteBuf out = EntityIdRewriter.rewriteServerbound( in, CLIENT, SERVER );
        DefinedPacket.readVarInt( out );
        assertEquals( SERVER, DefinedPacket.readVarInt( out ) );
        assertEquals( 1, DefinedPacket.readVarInt( out ) );
        assertEquals( 0, DefinedPacket.readVarInt( out ) );
    }

    @Test
    public void sameIdsDoNothing()
    {
        ByteBuf in = packet( Ids.SET_ENTITY_DATA );
        DefinedPacket.writeVarInt( CLIENT, in );
        assertNull( EntityIdRewriter.rewriteClientbound( in, CLIENT, CLIENT, P ) );
    }

    private static ByteBuf fireworkData(int attached, boolean withAngle)
    {
        ByteBuf in = packet( Ids.SET_ENTITY_DATA );
        DefinedPacket.writeVarInt( 4711, in ); // die Rakete selbst
        in.writeByte( 8 ); // Index 8: ItemStack, hier nur ein paar Bytes als Platzhalter
        DefinedPacket.writeVarInt( 7, in );
        in.writeBytes( new byte[]
        {
            1, 9, 19, 2, 3
        } );
        in.writeByte( Ids.FIREWORK_ATTACHED_INDEX );
        DefinedPacket.writeVarInt( Ids.SERIALIZER_OPTIONAL_UNSIGNED_INT, in );
        DefinedPacket.writeVarInt( attached + 1, in );
        if ( withAngle )
        {
            in.writeByte( 10 );
            DefinedPacket.writeVarInt( 8, in );
            in.writeBoolean( false );
        }
        in.writeByte( 0xFF );
        return in;
    }

    @Test
    public void fireworkTargetIsRewritten()
    {
        ByteBuf out = EntityIdRewriter.rewriteFireworkTarget( fireworkData( SERVER, true ), SERVER, CLIENT );
        assertNotNull( out );
        ByteBuf expected = fireworkData( CLIENT, true );
        assertEquals( expected, out );
    }

    @Test
    public void fireworkTargetAtEnd()
    {
        ByteBuf out = EntityIdRewriter.rewriteFireworkTarget( fireworkData( SERVER, false ), SERVER, CLIENT );
        assertEquals( fireworkData( CLIENT, false ), out );
    }

    @Test
    public void fireworkOfOtherPlayerStays()
    {
        assertNull( EntityIdRewriter.rewriteFireworkTarget( fireworkData( 1234, true ), SERVER, CLIENT ) );
    }

    @Test
    public void readerIndexIsLeftAlone()
    {
        ByteBuf in = packet( Ids.SET_ENTITY_DATA );
        DefinedPacket.writeVarInt( SERVER, in );
        int before = in.readerIndex();
        EntityIdRewriter.rewriteClientbound( in, SERVER, CLIENT, P );
        assertEquals( before, in.readerIndex() );
    }
}
