package net.md_5.bungee.seamless;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import net.md_5.bungee.protocol.DefinedPacket;
import net.md_5.bungee.seamless.SeamlessSwitch.Ids;

/**
 * Schreibt die Entity-ID des Spielers nach einem nahtlosen Wechsel um.
 *
 * Der Client kennt sich weiter unter der ID vom ersten Server (clientId), der
 * neue Server nennt ihn serverId. Server -> Client wird serverId zu clientId,
 * Client -> Server umgekehrt. Trägt ein anderes Wesen zufällig die jeweils
 * andere Nummer, wird getauscht, damit nichts doppelt belegt ist.
 *
 * Nur Protokoll 775. Pakete werden nicht im Ort verändert, sondern bei Bedarf
 * neu zusammengesetzt; ohne Treffer kommt null zurück.
 */
final class EntityIdRewriter
{

    private EntityIdRewriter()
    {
    }

    private static final class Edit
    {

        final int start;
        final int end;
        final int value;
        final boolean varInt;

        Edit(int start, int end, int value, boolean varInt)
        {
            this.start = start;
            this.end = end;
            this.value = value;
            this.varInt = varInt;
        }
    }

    private static int swap(int value, int from, int to)
    {
        if ( value == from )
        {
            return to;
        }
        if ( value == to )
        {
            return from;
        }
        return value;
    }

    // Liest ein VarInt und merkt eine Änderung vor, falls es umzuschreiben ist
    private static void varInt(ByteBuf buf, List<Edit> edits, int from, int to)
    {
        int start = buf.readerIndex();
        int value = DefinedPacket.readVarInt( buf );
        int replaced = swap( value, from, to );
        if ( replaced != value )
        {
            edits.add( new Edit( start, buf.readerIndex(), replaced, true ) );
        }
    }

    // Wie varInt, aber für Felder, die als ID + 1 kodiert sind (0 = keins)
    private static void varIntPlusOne(ByteBuf buf, List<Edit> edits, int from, int to)
    {
        int start = buf.readerIndex();
        int value = DefinedPacket.readVarInt( buf );
        if ( value > 0 )
        {
            int replaced = swap( value - 1, from, to );
            if ( replaced != value - 1 )
            {
                edits.add( new Edit( start, buf.readerIndex(), replaced + 1, true ) );
            }
        }
    }

    private static void fixedInt(ByteBuf buf, List<Edit> edits, int from, int to)
    {
        int start = buf.readerIndex();
        int value = buf.readInt();
        int replaced = swap( value, from, to );
        if ( replaced != value )
        {
            edits.add( new Edit( start, buf.readerIndex(), replaced, false ) );
        }
    }

    private static void varIntList(ByteBuf buf, List<Edit> edits, int from, int to)
    {
        int count = DefinedPacket.readVarInt( buf );
        for ( int i = 0; i < count; i++ )
        {
            varInt( buf, edits, from, to );
        }
    }

    // Vec3.LP_STREAM_CODEC (LpVec3): 0 = Nullvektor, sonst 6 Byte + evtl. VarInt
    private static void skipLpVec3(ByteBuf buf)
    {
        int lowest = buf.readUnsignedByte();
        if ( lowest != 0 )
        {
            buf.skipBytes( 5 );
            if ( ( lowest & 4 ) == 4 )
            {
                DefinedPacket.readVarInt( buf );
            }
        }
    }

    private static boolean ownerDataType(int type, int protocol)
    {
        for ( int t : Ids.OWNER_DATA_TYPES )
        {
            if ( Ids.entityType( t, protocol ) == type )
            {
                return true;
            }
        }
        return false;
    }

    // add_entity: ID, UUID, Typ, Position, Bewegung, Drehungen, data
    private static void addEntity(ByteBuf buf, List<Edit> edits, int from, int to, int protocol)
    {
        varInt( buf, edits, from, to );
        buf.skipBytes( 16 );
        int type = DefinedPacket.readVarInt( buf );
        if ( ownerDataType( type, protocol ) )
        {
            buf.skipBytes( 24 );
            skipLpVec3( buf );
            buf.skipBytes( 3 );
            varInt( buf, edits, from, to );
        }
    }

    // sound_entity: Holder<SoundEvent> (ID + 1, 0 = direkt), Quelle, Entity-ID
    private static void soundEntity(ByteBuf buf, List<Edit> edits, int from, int to)
    {
        int holder = DefinedPacket.readVarInt( buf );
        if ( holder == 0 )
        {
            DefinedPacket.readString( buf );
            if ( buf.readBoolean() )
            {
                buf.skipBytes( 4 );
            }
        }
        DefinedPacket.readVarInt( buf );
        varInt( buf, edits, from, to );
    }

    // Pakete, in denen Entity-IDs stehen (Vorfilter, damit der Rest nichts kostet)
    private static final boolean[] CLIENTBOUND = new boolean[ 256 ];
    private static final boolean[] SERVERBOUND = new boolean[ 256 ];

    static
    {
        int[] clientbound =
        {
            Ids.ANIMATE, Ids.BLOCK_DESTRUCTION, Ids.ENTITY_POSITION_SYNC, Ids.HURT_ANIMATION, Ids.MOVE_ENTITY_POS,
            Ids.MOVE_ENTITY_POS_ROT, Ids.MOVE_MINECART, Ids.MOVE_ENTITY_ROT, Ids.PLAYER_COMBAT_KILL, Ids.REMOVE_MOB_EFFECT,
            Ids.ROTATE_HEAD, Ids.SET_CAMERA, Ids.SET_ENTITY_DATA, Ids.SET_ENTITY_MOTION, Ids.SET_EQUIPMENT,
            Ids.TELEPORT_ENTITY, Ids.UPDATE_ATTRIBUTES, Ids.UPDATE_MOB_EFFECT, Ids.PROJECTILE_POWER, Ids.ENTITY_EVENT,
            Ids.SET_ENTITY_LINK, Ids.ADD_ENTITY, Ids.DAMAGE_EVENT, Ids.SET_PASSENGERS, Ids.REMOVE_ENTITIES,
            Ids.TAKE_ITEM_ENTITY, Ids.SOUND_ENTITY, Ids.PLAYER_LOOK_AT
        };
        for ( int id : clientbound )
        {
            CLIENTBOUND[id] = true;
        }
        int[] serverbound =
        {
            Ids.SB_ATTACK, Ids.SB_INTERACT, Ids.SB_PICK_ITEM_FROM_ENTITY, Ids.SB_PLAYER_COMMAND, Ids.SB_ENTITY_TAG_QUERY
        };
        for ( int id : serverbound )
        {
            SERVERBOUND[id] = true;
        }
    }

    private static boolean contains(boolean[] table, ByteBuf packet)
    {
        int id = SeamlessSwitch.peekPacketId( packet );
        return id >= 0 && id < table.length && table[id];
    }

    // Server -> Client: serverId wird zu clientId; protocol = Protokoll des Clients (für Entity-Typen)
    static ByteBuf rewriteClientbound(ByteBuf packet, int serverId, int clientId, int protocol)
    {
        if ( serverId == clientId || !contains( CLIENTBOUND, packet ) )
        {
            return null;
        }
        ByteBuf buf = packet.duplicate();
        List<Edit> edits = new ArrayList<>( 2 );
        try
        {
            int id = DefinedPacket.readVarInt( buf );
            switch ( id )
            {
                case Ids.ANIMATE:
                case Ids.BLOCK_DESTRUCTION:
                case Ids.ENTITY_POSITION_SYNC:
                case Ids.HURT_ANIMATION:
                case Ids.MOVE_ENTITY_POS:
                case Ids.MOVE_ENTITY_POS_ROT:
                case Ids.MOVE_MINECART:
                case Ids.MOVE_ENTITY_ROT:
                case Ids.PLAYER_COMBAT_KILL:
                case Ids.REMOVE_MOB_EFFECT:
                case Ids.ROTATE_HEAD:
                case Ids.SET_CAMERA:
                case Ids.SET_ENTITY_DATA:
                case Ids.SET_ENTITY_MOTION:
                case Ids.SET_EQUIPMENT:
                case Ids.TELEPORT_ENTITY:
                case Ids.UPDATE_ATTRIBUTES:
                case Ids.UPDATE_MOB_EFFECT:
                case Ids.PROJECTILE_POWER:
                    varInt( buf, edits, serverId, clientId );
                    break;
                case Ids.ENTITY_EVENT:
                    fixedInt( buf, edits, serverId, clientId );
                    break;
                case Ids.SET_ENTITY_LINK:
                    fixedInt( buf, edits, serverId, clientId );
                    fixedInt( buf, edits, serverId, clientId );
                    break;
                case Ids.ADD_ENTITY:
                    addEntity( buf, edits, serverId, clientId, protocol );
                    break;
                case Ids.DAMAGE_EVENT:
                    varInt( buf, edits, serverId, clientId );
                    DefinedPacket.readVarInt( buf ); // Schadensart
                    varIntPlusOne( buf, edits, serverId, clientId );
                    varIntPlusOne( buf, edits, serverId, clientId );
                    break;
                case Ids.SET_PASSENGERS:
                    varInt( buf, edits, serverId, clientId );
                    varIntList( buf, edits, serverId, clientId );
                    break;
                case Ids.REMOVE_ENTITIES:
                    varIntList( buf, edits, serverId, clientId );
                    break;
                case Ids.TAKE_ITEM_ENTITY:
                    varInt( buf, edits, serverId, clientId );
                    varInt( buf, edits, serverId, clientId );
                    break;
                case Ids.SOUND_ENTITY:
                    soundEntity( buf, edits, serverId, clientId );
                    break;
                case Ids.PLAYER_LOOK_AT:
                    DefinedPacket.readVarInt( buf );
                    buf.skipBytes( 24 );
                    if ( buf.readBoolean() )
                    {
                        varInt( buf, edits, serverId, clientId );
                    }
                    break;
                default:
                    return null;
            }
        } catch ( RuntimeException ex )
        {
            // Unerwarteter Aufbau: lieber unverändert durchreichen als kaputt machen
            return null;
        }
        return apply( packet, edits );
    }

    // Client -> Server: clientId wird zu serverId
    static ByteBuf rewriteServerbound(ByteBuf packet, int clientId, int serverId)
    {
        if ( serverId == clientId || !contains( SERVERBOUND, packet ) )
        {
            return null;
        }
        ByteBuf buf = packet.duplicate();
        List<Edit> edits = new ArrayList<>( 1 );
        try
        {
            int id = DefinedPacket.readVarInt( buf );
            switch ( id )
            {
                case Ids.SB_ATTACK:
                case Ids.SB_INTERACT:
                case Ids.SB_PICK_ITEM_FROM_ENTITY:
                case Ids.SB_PLAYER_COMMAND:
                    varInt( buf, edits, clientId, serverId );
                    break;
                case Ids.SB_ENTITY_TAG_QUERY:
                    DefinedPacket.readVarInt( buf );
                    varInt( buf, edits, clientId, serverId );
                    break;
                default:
                    return null;
            }
        } catch ( RuntimeException ex )
        {
            return null;
        }
        return apply( packet, edits );
    }

    /*
     * set_entity_data einer Feuerwerksrakete: Index 9 (OPTIONAL_UNSIGNED_INT, ID + 1) ist der
     * Spieler, an dem sie beim Elytra-Boost hängt. Davor kann ein ItemStack (Index 8) stehen,
     * dessen Länge sich nicht ohne Weiteres bestimmen lässt. Deshalb wird von hinten nach dem
     * Eintrag gesucht: Index 9, Typ 19, VarInt, danach Index 10 (Boolean) oder das Ende 0xFF.
     */
    static ByteBuf rewriteFireworkTarget(ByteBuf packet, int serverId, int clientId)
    {
        if ( serverId == clientId )
        {
            return null;
        }
        int start = packet.readerIndex();
        int end = packet.writerIndex();
        for ( int i = end - 3; i > start; i-- )
        {
            if ( packet.getByte( i ) != Ids.FIREWORK_ATTACHED_INDEX || packet.getByte( i + 1 ) != Ids.SERIALIZER_OPTIONAL_UNSIGNED_INT )
            {
                continue;
            }
            ByteBuf buf = packet.duplicate();
            buf.readerIndex( i + 2 );
            int valueStart = buf.readerIndex();
            int value;
            try
            {
                value = DefinedPacket.readVarInt( buf );
            } catch ( RuntimeException ex )
            {
                continue;
            }
            int valueEnd = buf.readerIndex();
            boolean followedByEnd = valueEnd == end - 1 && packet.getUnsignedByte( valueEnd ) == 0xFF;
            boolean followedByIndex10 = valueEnd + 1 < end && packet.getByte( valueEnd ) == 10 && packet.getByte( valueEnd + 1 ) == 8;
            if ( !followedByEnd && !followedByIndex10 )
            {
                continue;
            }
            if ( value <= 0 )
            {
                return null;
            }
            int replaced = swap( value - 1, serverId, clientId );
            if ( replaced == value - 1 )
            {
                return null;
            }
            List<Edit> edits = new ArrayList<>( 1 );
            edits.add( new Edit( valueStart, valueEnd, replaced + 1, true ) );
            return apply( packet, edits );
        }
        return null;
    }

    private static ByteBuf apply(ByteBuf packet, List<Edit> edits)
    {
        if ( edits.isEmpty() )
        {
            return null;
        }
        ByteBuf out = Unpooled.buffer( packet.readableBytes() + 8 );
        int position = packet.readerIndex();
        for ( Edit edit : edits )
        {
            out.writeBytes( packet, position, edit.start - position );
            if ( edit.varInt )
            {
                DefinedPacket.writeVarInt( edit.value, out );
            } else
            {
                out.writeInt( edit.value );
            }
            position = edit.end;
        }
        out.writeBytes( packet, position, packet.writerIndex() - position );
        return out;
    }
}
