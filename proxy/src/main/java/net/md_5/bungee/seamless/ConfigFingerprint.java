package net.md_5.bungee.seamless;

import io.netty.buffer.ByteBuf;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import net.md_5.bungee.protocol.DefinedPacket;
import net.md_5.bungee.seamless.SeamlessSwitch.Ids;

/**
 * Fingerabdruck der Konfiguration eines Servers: je Registry, je Tag-Registry, Features
 * und KnownPacks ein Teil. Zwei Serverprozesse mit gleichem Inhalt schicken manches
 * nicht in derselben Reihenfolge (Leaf sammelt Tags über IdentityHashMap und HashMap,
 * NBT-Compounds der Registry-Einträge haben keine feste Schlüsselfolge). Deshalb sind
 * Tags nach Namen und Compound-Schlüssel sortiert, der Gesamtabdruck nach Teilen. Die
 * Reihenfolge der Registry-Einträge zählt dagegen: Sie legt die Zahlen-IDs fest.
 */
final class ConfigFingerprint
{

    // Teil -> SHA-256 des Teils
    private final TreeMap<String, byte[]> parts = new TreeMap<>();
    // Für das Log: je Registry-Teil die Einträge in Reihenfolge mit ihrem Hash
    private final Map<String, LinkedHashMap<String, byte[]>> registryEntries = new HashMap<>();
    // Für das Log: je Tag-Teil die Tag-Namen (sortiert) und ein kurzer Hash ihrer ID-Liste. Bewusst schmal
    // (Arrays, Namen interned): jeder Spieler hält einen Abdruck, und allein minecraft:block hat Hunderte Tags.
    private final Map<String, TagList> tagEntries = new HashMap<>();
    private byte[] total;

    private static final class TagList
    {

        private final String[] names;
        private final int[] hashes;

        private TagList(Map<String, byte[]> tags)
        {
            names = new String[ tags.size() ];
            hashes = new int[ tags.size() ];
            int i = 0;
            for ( Map.Entry<String, byte[]> tag : tags.entrySet() )
            {
                names[i] = tag.getKey().intern();
                hashes[i] = Arrays.hashCode( tag.getValue() );
                i++;
            }
        }
    }

    // Rohes Konfig-Paket (mit Paketnummer)
    void addPacket(ByteBuf packet)
    {
        total = null;
        ByteBuf buf = packet.duplicate();
        int id = DefinedPacket.readVarInt( buf );
        try
        {
            switch ( id )
            {
                case Ids.CFG_REGISTRY_DATA:
                    addRegistry( buf );
                    return;
                case Ids.CFG_UPDATE_TAGS:
                    addTags( buf );
                    return;
                case Ids.CFG_UPDATE_ENABLED_FEATURES:
                    addFeatures( buf );
                    return;
                default:
                    break;
            }
        } catch ( RuntimeException ex )
        {
            // Nicht lesbar: unten roh übernehmen, dann vergleicht es eben byteweise
        }
        put( "paket " + id, hash( bytes( packet ) ) );
    }

    void addKnownPacks(String text)
    {
        total = null;
        put( "knownpacks", hash( text.getBytes( StandardCharsets.UTF_8 ) ) );
    }

    // registry_data: Name, Anzahl Einträge, je Eintrag ID + optional NBT (ohne Namen)
    private void addRegistry(ByteBuf buf)
    {
        String registry = DefinedPacket.readString( buf );
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        MessageDigest digest = newDigest();
        int count = DefinedPacket.readVarInt( buf );
        for ( int i = 0; i < count; i++ )
        {
            String entry = DefinedPacket.readString( buf );
            byte[] entryHash = hash( buf.readBoolean() ? canonicalNbt( buf ) : new byte[ 0 ] );
            entries.put( entry, entryHash );
            digest.update( entry.getBytes( StandardCharsets.UTF_8 ) );
            digest.update( (byte) 0 );
            digest.update( entryHash );
        }
        if ( buf.isReadable() )
        {
            throw new IllegalStateException( "Rest im Registry-Paket" );
        }
        registryEntries.put( put( "registry " + registry, digest.digest() ), entries );
    }

    // update_tags: Anzahl Registries, je Registry Name + Anzahl Tags, je Tag Name + ID-Liste
    private void addTags(ByteBuf buf)
    {
        Map<String, byte[]> registries = new TreeMap<>();
        Map<String, TagList> lists = new HashMap<>();
        int registryCount = DefinedPacket.readVarInt( buf );
        for ( int r = 0; r < registryCount; r++ )
        {
            String registry = DefinedPacket.readString( buf );
            TreeMap<String, byte[]> tags = new TreeMap<>();
            int tagCount = DefinedPacket.readVarInt( buf );
            for ( int t = 0; t < tagCount; t++ )
            {
                String tag = DefinedPacket.readString( buf );
                int start = buf.readerIndex();
                int entries = DefinedPacket.readVarInt( buf );
                for ( int e = 0; e < entries; e++ )
                {
                    DefinedPacket.readVarInt( buf );
                }
                byte[] ids = new byte[ buf.readerIndex() - start ];
                buf.getBytes( start, ids );
                tags.put( tag, ids );
            }
            MessageDigest digest = newDigest();
            for ( Map.Entry<String, byte[]> tag : tags.entrySet() )
            {
                digest.update( tag.getKey().getBytes( StandardCharsets.UTF_8 ) );
                digest.update( (byte) 0 );
                digest.update( tag.getValue() );
            }
            registries.put( registry, digest.digest() );
            lists.put( registry, new TagList( tags ) );
        }
        for ( Map.Entry<String, byte[]> registry : registries.entrySet() )
        {
            tagEntries.put( put( "tags " + registry.getKey(), registry.getValue() ), lists.get( registry.getKey() ) );
        }
    }

    private void addFeatures(ByteBuf buf)
    {
        TreeSet<String> features = new TreeSet<>();
        int count = DefinedPacket.readVarInt( buf );
        for ( int i = 0; i < count; i++ )
        {
            features.add( DefinedPacket.readString( buf ) );
        }
        put( "features", hash( String.join( ";", features ).getBytes( StandardCharsets.UTF_8 ) ) );
    }

    private String put(String key, byte[] value)
    {
        // Kommt ein Teil doppelt (sollte nicht), zählen beide
        String unique = key;
        for ( int n = 2; parts.containsKey( unique ); n++ )
        {
            unique = key + " #" + n;
        }
        parts.put( unique, value );
        return unique;
    }

    /*========================================================================*/
    // NBT in fester Form: Compound-Schlüssel sortiert, sonst Byte für Byte wie gesendet

    private static byte[] canonicalNbt(ByteBuf buf)
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int type = buf.readUnsignedByte();
        out.write( type );
        canonicalPayload( buf, type, out, 0 );
        return out.toByteArray();
    }

    private static void canonicalPayload(ByteBuf buf, int type, ByteArrayOutputStream out, int depth)
    {
        if ( depth > 512 )
        {
            throw new IllegalStateException( "NBT zu tief verschachtelt" );
        }
        switch ( type )
        {
            case 0:
                return;
            case 1:
                copy( buf, out, 1 );
                return;
            case 2:
                copy( buf, out, 2 );
                return;
            case 3:
            case 5:
                copy( buf, out, 4 );
                return;
            case 4:
            case 6:
                copy( buf, out, 8 );
                return;
            case 7:
                copyArray( buf, out, 1 );
                return;
            case 8:
                copyString( buf, out );
                return;
            case 9:
                canonicalList( buf, out, depth );
                return;
            case 10:
                canonicalCompound( buf, out, depth );
                return;
            case 11:
                copyArray( buf, out, 4 );
                return;
            case 12:
                copyArray( buf, out, 8 );
                return;
            default:
                throw new IllegalStateException( "unbekannter NBT-Typ " + type );
        }
    }

    private static void canonicalList(ByteBuf buf, ByteArrayOutputStream out, int depth)
    {
        int elementType = buf.readUnsignedByte();
        int length = buf.readInt();
        if ( length < 0 || length > buf.readableBytes() )
        {
            throw new IllegalStateException( "NBT-Liste zu lang" );
        }
        out.write( elementType );
        writeInt( out, length );
        for ( int i = 0; i < length; i++ )
        {
            canonicalPayload( buf, elementType, out, depth + 1 );
        }
    }

    private static void canonicalCompound(ByteBuf buf, ByteArrayOutputStream out, int depth)
    {
        TreeMap<String, byte[]> entries = new TreeMap<>();
        while ( true )
        {
            int type = buf.readUnsignedByte();
            if ( type == 0 )
            {
                break;
            }
            ByteArrayOutputStream entry = new ByteArrayOutputStream();
            entry.write( type );
            byte[] name = stringBytes( buf );
            entry.write( name, 0, name.length );
            canonicalPayload( buf, type, entry, depth + 1 );
            // Schlüssel byteweise vergleichen, ISO-8859-1 bildet jedes Byte auf genau ein Zeichen ab
            entries.put( new String( name, StandardCharsets.ISO_8859_1 ), entry.toByteArray() );
        }
        for ( byte[] entry : entries.values() )
        {
            out.write( entry, 0, entry.length );
        }
        out.write( 0 );
    }

    // NBT-String samt Längenpräfix
    private static byte[] stringBytes(ByteBuf buf)
    {
        int length = buf.readUnsignedShort();
        byte[] bytes = new byte[ 2 + length ];
        bytes[0] = (byte) ( length >>> 8 );
        bytes[1] = (byte) length;
        buf.readBytes( bytes, 2, length );
        return bytes;
    }

    private static void copyString(ByteBuf buf, ByteArrayOutputStream out)
    {
        byte[] bytes = stringBytes( buf );
        out.write( bytes, 0, bytes.length );
    }

    private static void copyArray(ByteBuf buf, ByteArrayOutputStream out, int elementSize)
    {
        int length = buf.readInt();
        if ( length < 0 || length > buf.readableBytes() / elementSize )
        {
            throw new IllegalStateException( "NBT-Array zu lang" );
        }
        writeInt( out, length );
        copy( buf, out, length * elementSize );
    }

    private static void copy(ByteBuf buf, ByteArrayOutputStream out, int length)
    {
        byte[] bytes = new byte[ length ];
        buf.readBytes( bytes );
        out.write( bytes, 0, length );
    }

    private static void writeInt(ByteArrayOutputStream out, int value)
    {
        out.write( value >>> 24 );
        out.write( value >>> 16 );
        out.write( value >>> 8 );
        out.write( value );
    }

    byte[] total()
    {
        if ( total == null )
        {
            MessageDigest digest = newDigest();
            for ( Map.Entry<String, byte[]> part : parts.entrySet() )
            {
                digest.update( part.getKey().getBytes( StandardCharsets.UTF_8 ) );
                digest.update( (byte) 0 );
                digest.update( part.getValue() );
            }
            total = digest.digest();
        }
        return total;
    }

    boolean sameAs(ConfigFingerprint other)
    {
        return other != null && MessageDigest.isEqual( total(), other.total() );
    }

    // Für das Log: welche Teile weichen ab (höchstens ein paar)
    String describeDifference(ConfigFingerprint other)
    {
        if ( other == null )
        {
            return "kein Vergleich";
        }
        TreeSet<String> keys = new TreeSet<>( parts.keySet() );
        keys.addAll( other.parts.keySet() );
        List<String> differing = new ArrayList<>();
        for ( String key : keys )
        {
            byte[] mine = parts.get( key );
            byte[] theirs = other.parts.get( key );
            if ( mine == null )
            {
                differing.add( key + " (fehlt hier)" );
            } else if ( theirs == null )
            {
                differing.add( key + " (nur hier)" );
            } else if ( !Arrays.equals( mine, theirs ) )
            {
                differing.add( tagEntries.containsKey( key ) ? tagDetail( key, other ) : registryDetail( key, other ) );
            }
        }
        if ( differing.isEmpty() )
        {
            return "keine Teile verschieden";
        }
        int shown = Math.min( 6, differing.size() );
        String text = String.join( ", ", differing.subList( 0, shown ) );
        return differing.size() > shown ? text + " (+" + ( differing.size() - shown ) + " weitere)" : text;
    }

    // Bei einer Registry: welche Einträge abweichen, oder nur die Reihenfolge
    private String registryDetail(String key, ConfigFingerprint other)
    {
        LinkedHashMap<String, byte[]> mine = registryEntries.get( key );
        LinkedHashMap<String, byte[]> theirs = other.registryEntries.get( key );
        if ( mine == null || theirs == null )
        {
            return key;
        }
        List<String> changed = new ArrayList<>();
        for ( Map.Entry<String, byte[]> entry : mine.entrySet() )
        {
            byte[] theirsHash = theirs.get( entry.getKey() );
            if ( theirsHash == null )
            {
                changed.add( entry.getKey() + " nur hier" );
            } else if ( !Arrays.equals( theirsHash, entry.getValue() ) )
            {
                changed.add( entry.getKey() );
            }
        }
        for ( String entry : theirs.keySet() )
        {
            if ( !mine.containsKey( entry ) )
            {
                changed.add( entry + " fehlt hier" );
            }
        }
        if ( changed.isEmpty() )
        {
            return key + " (Reihenfolge der Einträge anders)";
        }
        int shown = Math.min( 4, changed.size() );
        String text = String.join( ", ", changed.subList( 0, shown ) );
        return key + " (" + ( changed.size() > shown ? text + " +" + ( changed.size() - shown ) : text ) + ")";
    }

    // Bei Tags: welche Tags abweichen (beide Listen sind nach Namen sortiert)
    private String tagDetail(String key, ConfigFingerprint other)
    {
        TagList mine = tagEntries.get( key );
        TagList theirs = other.tagEntries.get( key );
        if ( mine == null || theirs == null )
        {
            return key;
        }
        List<String> changed = new ArrayList<>();
        int i = 0;
        int j = 0;
        while ( i < mine.names.length || j < theirs.names.length )
        {
            int order = i == mine.names.length ? 1 : j == theirs.names.length ? -1 : mine.names[i].compareTo( theirs.names[j] );
            if ( order < 0 )
            {
                changed.add( mine.names[i++] + " nur hier" );
            } else if ( order > 0 )
            {
                changed.add( theirs.names[j++] + " fehlt hier" );
            } else
            {
                if ( mine.hashes[i] != theirs.hashes[j] )
                {
                    changed.add( mine.names[i] );
                }
                i++;
                j++;
            }
        }
        if ( changed.isEmpty() )
        {
            return key;
        }
        int shown = Math.min( 4, changed.size() );
        String text = String.join( ", ", changed.subList( 0, shown ) );
        return key + " (" + ( changed.size() > shown ? text + " +" + ( changed.size() - shown ) : text ) + ")";
    }

    private static byte[] bytes(ByteBuf buf)
    {
        byte[] bytes = new byte[ buf.readableBytes() ];
        buf.getBytes( buf.readerIndex(), bytes );
        return bytes;
    }

    private static byte[] hash(byte[] data)
    {
        return newDigest().digest( data );
    }

    private static MessageDigest newDigest()
    {
        try
        {
            return MessageDigest.getInstance( "SHA-256" );
        } catch ( NoSuchAlgorithmException ex )
        {
            throw new IllegalStateException( ex );
        }
    }
}
