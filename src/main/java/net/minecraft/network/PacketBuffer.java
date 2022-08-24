package net.minecraft.network;

import com.google.common.base.Charsets;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import io.netty.util.ByteProcessor;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTSizeTracker;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.BlockPos;
import net.minecraft.util.IChatComponent;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.GatheringByteChannel;
import java.nio.channels.ScatteringByteChannel;
import java.nio.charset.Charset;
import java.util.UUID;

public class PacketBuffer extends ByteBuf
{
    private final ByteBuf buf;

    public PacketBuffer(ByteBuf wrapped)
    {
        this.buf = wrapped;
    }

    /**
     * Calculates the number of bytes required to fit the supplied int (0-5) if it were to be read/written using
     * readVarIntFromBuffer or writeVarIntToBuffer
     */
    public static int getVarIntSize(int input)
    {
        for (int i = 1; i < 5; ++i)
        {
            if ((input & -1 << i * 7) == 0)
            {
                return i;
            }
        }

        return 5;
    }

    public void writeByteArray(byte[] array)
    {
        this.writeVarIntToBuffer(array.length);
        this.writeBytes(array);
    }

    public byte[] readByteArray()
    {
        byte[] abyte = new byte[this.readVarIntFromBuffer()];
        this.readBytes(abyte);
        return abyte;
    }

    public BlockPos readBlockPos()
    {
        return BlockPos.fromLong(this.readLong());
    }

    public void writeBlockPos(BlockPos pos)
    {
        this.writeLong(pos.toLong());
    }

    public IChatComponent readChatComponent() throws IOException
    {
        return IChatComponent.Serializer.jsonToComponent(this.readStringFromBuffer(32767));
    }

    public void writeChatComponent(IChatComponent component) throws IOException
    {
        this.writeString(IChatComponent.Serializer.componentToJson(component));
    }

    public <T extends Enum<T>> T readEnumValue(Class<T> enumClass)
    {
        return enumClass.getEnumConstants()[this.readVarIntFromBuffer()]; // Fixed useless casts
    }

    public void writeEnumValue(Enum<?> value)
    {
        this.writeVarIntToBuffer(value.ordinal());
    }

    /**
     * Reads a compressed int from the buffer. To do so it maximally reads 5 byte-sized chunks whose most significant
     * bit dictates whether another byte should be read.
     */
    public int readVarIntFromBuffer()
    {
        int i = 0;
        int j = 0;

        while (true)
        {
            byte b0 = this.readByte();
            i |= (b0 & 127) << j++ * 7;

            if (j > 5)
            {
                throw new RuntimeException("VarInt too big");
            }

            if ((b0 & 128) != 128)
            {
                break;
            }
        }

        return i;
    }

    public long readVarLong()
    {
        long i = 0L;
        int j = 0;

        while (true)
        {
            byte b0 = this.readByte();
            i |= (long)(b0 & 127) << j++ * 7;

            if (j > 10)
            {
                throw new RuntimeException("VarLong too big");
            }

            if ((b0 & 128) != 128)
            {
                break;
            }
        }

        return i;
    }

    public void writeUuid(UUID uuid)
    {
        this.writeLong(uuid.getMostSignificantBits());
        this.writeLong(uuid.getLeastSignificantBits());
    }

    public UUID readUuid()
    {
        return new UUID(this.readLong(), this.readLong());
    }

    /**
     * Writes a compressed int to the buffer. The smallest number of bytes to fit the passed int will be written. Of
     * each such byte only 7 bits will be used to describe the actual value since its most significant bit dictates
     * whether the next byte is part of that same int. Micro-optimization for int values that are expected to have
     * values below 128.
     */
    public void writeVarIntToBuffer(int input)
    {
        while ((input & -128) != 0)
        {
            this.writeByte(input & 127 | 128);
            input >>>= 7;
        }

        this.writeByte(input);
    }

    public void writeVarLong(long value)
    {
        while ((value & -128L) != 0L)
        {
            this.writeByte((int)(value & 127L) | 128);
            value >>>= 7;
        }

        this.writeByte((int)value);
    }

    /**
     * Writes a compressed NBTTagCompound to this buffer
     */
    public void writeNBTTagCompoundToBuffer(NBTTagCompound nbt)
    {
        if (nbt == null)
        {
            this.writeByte(0);
        }
        else
        {
            try
            {
                CompressedStreamTools.write(nbt, new ByteBufOutputStream(this));
            }
            catch (IOException ioexception)
            {
                throw new EncoderException(ioexception);
            }
        }
    }

    /**
     * Reads a compressed NBTTagCompound from this buffer
     */
    public NBTTagCompound readNBTTagCompoundFromBuffer() throws IOException
    {
        int i = this.readerIndex();
        byte b0 = this.readByte();

        if (b0 == 0)
        {
            return null;
        }
        else
        {
            this.readerIndex(i);
            return CompressedStreamTools.read(new ByteBufInputStream(this), new NBTSizeTracker(2097152L));
        }
    }

    /**
     * Writes the ItemStack's ID (short), then size (byte), then damage. (short)
     */
    public void writeItemStackToBuffer(ItemStack stack)
    {
        if (stack == null)
        {
            this.writeShort(-1);
        }
        else
        {
            this.writeShort(Item.getIdFromItem(stack.getItem()));
            this.writeByte(stack.stackSize);
            this.writeShort(stack.getMetadata());
            NBTTagCompound nbttagcompound = null;

            if (stack.getItem().isDamageable() || stack.getItem().getShareTag())
            {
                nbttagcompound = stack.getTagCompound();
            }

            this.writeNBTTagCompoundToBuffer(nbttagcompound);
        }
    }

    /**
     * Reads an ItemStack from this buffer
     */
    public ItemStack readItemStackFromBuffer() throws IOException
    {
        ItemStack itemstack = null;
        int i = this.readShort();

        if (i >= 0)
        {
            int j = this.readByte();
            int k = this.readShort();
            itemstack = new ItemStack(Item.getItemById(i), j, k);
            itemstack.setTagCompound(this.readNBTTagCompoundFromBuffer());
        }

        return itemstack;
    }

    /**
     * Reads a string from this buffer. Expected parameter is maximum allowed string length. Will throw IOException if
     * string length exceeds this value!
     */
    public String readStringFromBuffer(int maxLength)
    {
        int i = this.readVarIntFromBuffer();

        if (i > maxLength * 4)
        {
            throw new DecoderException("The received encoded string buffer length is longer than maximum allowed (" + i + " > " + (maxLength * 4) + ")");
        }
        else if (i < 0)
        {
            throw new DecoderException("The received encoded string buffer length is less than zero! Weird string!");
        }
        else {
            byte[] buffer = new byte[i];
            this.readBytes(buffer);
            String s = new String(buffer, Charsets.UTF_8);

            if (s.length() > maxLength) {
                throw new DecoderException("The received string length is longer than maximum allowed (" + i + " > " + maxLength + ")");
            } else {
                return s;
            }
        }
    }

    public PacketBuffer writeString(String string)
    {
        byte[] abyte = string.getBytes(Charsets.UTF_8);

        if (abyte.length > 32767)
        {
            throw new EncoderException("String too big (was " + string.length() + " bytes encoded, max " + 32767 + ")");
        } else {
            this.writeVarIntToBuffer(abyte.length);
            this.writeBytes(abyte);
            return this;
        }
    }

    //Delegate to buf

    @Override
    public int capacity() {
        return buf.capacity();
    }

    @Override
    public ByteBuf capacity(int newCapacity) {
        return buf.capacity(newCapacity);
    }

    @Override
    public int maxCapacity() {
        return buf.maxCapacity();
    }

    @Override
    public ByteBufAllocator alloc() {
        return buf.alloc();
    }

    @Override
    @Deprecated
    public ByteOrder order() {
        return buf.order();
    }

    @Override
    @Deprecated
    public ByteBuf order(ByteOrder endianness) {
        return buf.order(endianness);
    }

    @Override
    public ByteBuf unwrap() {
        return buf.unwrap();
    }

    @Override
    public boolean isDirect() {
        return buf.isDirect();
    }

    @Override
    public boolean isReadOnly() {
        return buf.isReadOnly();
    }

    @Override
    public ByteBuf asReadOnly() {
        return buf.asReadOnly();
    }

    @Override
    public int readerIndex() {
        return buf.readerIndex();
    }

    @Override
    public ByteBuf readerIndex(int readerIndex) {
        return buf.readerIndex(readerIndex);
    }

    @Override
    public int writerIndex() {
        return buf.writerIndex();
    }

    @Override
    public ByteBuf writerIndex(int writerIndex) {
        return buf.writerIndex(writerIndex);
    }

    @Override
    public ByteBuf setIndex(int readerIndex, int writerIndex) {
        return buf.setIndex(readerIndex, writerIndex);
    }

    @Override
    public int readableBytes() {
        return buf.readableBytes();
    }

    @Override
    public int writableBytes() {
        return buf.writableBytes();
    }

    @Override
    public int maxWritableBytes() {
        return buf.maxWritableBytes();
    }

    @Override
    public int maxFastWritableBytes() {
        return buf.maxFastWritableBytes();
    }

    @Override
    public boolean isReadable() {
        return buf.isReadable();
    }

    @Override
    public boolean isReadable(int size) {
        return buf.isReadable(size);
    }

    @Override
    public boolean isWritable() {
        return buf.isWritable();
    }

    @Override
    public boolean isWritable(int size) {
        return buf.isWritable(size);
    }

    @Override
    public ByteBuf clear() {
        return buf.clear();
    }

    @Override
    public ByteBuf markReaderIndex() {
        return buf.markReaderIndex();
    }

    @Override
    public ByteBuf resetReaderIndex() {
        return buf.resetReaderIndex();
    }

    @Override
    public ByteBuf markWriterIndex() {
        return buf.markWriterIndex();
    }

    @Override
    public ByteBuf resetWriterIndex() {
        return buf.resetWriterIndex();
    }

    @Override
    public ByteBuf discardReadBytes() {
        return buf.discardReadBytes();
    }

    @Override
    public ByteBuf discardSomeReadBytes() {
        return buf.discardSomeReadBytes();
    }

    @Override
    public ByteBuf ensureWritable(int minWritableBytes) {
        return buf.ensureWritable(minWritableBytes);
    }

    @Override
    public int ensureWritable(int minWritableBytes, boolean force) {
        return buf.ensureWritable(minWritableBytes, force);
    }

    @Override
    public boolean getBoolean(int index) {
        return buf.getBoolean(index);
    }

    @Override
    public byte getByte(int index) {
        return buf.getByte(index);
    }

    @Override
    public short getUnsignedByte(int index) {
        return buf.getUnsignedByte(index);
    }

    @Override
    public short getShort(int index) {
        return buf.getShort(index);
    }

    @Override
    public short getShortLE(int index) {
        return buf.getShortLE(index);
    }

    @Override
    public int getUnsignedShort(int index) {
        return buf.getUnsignedShort(index);
    }

    @Override
    public int getUnsignedShortLE(int index) {
        return buf.getUnsignedShortLE(index);
    }

    @Override
    public int getMedium(int index) {
        return buf.getMedium(index);
    }

    @Override
    public int getMediumLE(int index) {
        return buf.getMediumLE(index);
    }

    @Override
    public int getUnsignedMedium(int index) {
        return buf.getUnsignedMedium(index);
    }

    @Override
    public int getUnsignedMediumLE(int index) {
        return buf.getUnsignedMediumLE(index);
    }

    @Override
    public int getInt(int index) {
        return buf.getInt(index);
    }

    @Override
    public int getIntLE(int index) {
        return buf.getIntLE(index);
    }

    @Override
    public long getUnsignedInt(int index) {
        return buf.getUnsignedInt(index);
    }

    @Override
    public long getUnsignedIntLE(int index) {
        return buf.getUnsignedIntLE(index);
    }

    @Override
    public long getLong(int index) {
        return buf.getLong(index);
    }

    @Override
    public long getLongLE(int index) {
        return buf.getLongLE(index);
    }

    @Override
    public char getChar(int index) {
        return buf.getChar(index);
    }

    @Override
    public float getFloat(int index) {
        return buf.getFloat(index);
    }

    @Override
    public float getFloatLE(int index) {
        return buf.getFloatLE(index);
    }

    @Override
    public double getDouble(int index) {
        return buf.getDouble(index);
    }

    @Override
    public double getDoubleLE(int index) {
        return buf.getDoubleLE(index);
    }

    @Override
    public ByteBuf getBytes(int index, ByteBuf dst) {
        return buf.getBytes(index, dst);
    }

    @Override
    public ByteBuf getBytes(int index, ByteBuf dst, int length) {
        return buf.getBytes(index, dst, length);
    }

    @Override
    public ByteBuf getBytes(int index, ByteBuf dst, int dstIndex, int length) {
        return buf.getBytes(index, dst, dstIndex, length);
    }

    @Override
    public ByteBuf getBytes(int index, byte[] dst) {
        return buf.getBytes(index, dst);
    }

    @Override
    public ByteBuf getBytes(int index, byte[] dst, int dstIndex, int length) {
        return buf.getBytes(index, dst, dstIndex, length);
    }

    @Override
    public ByteBuf getBytes(int index, ByteBuffer dst) {
        return buf.getBytes(index, dst);
    }

    @Override
    public ByteBuf getBytes(int index, OutputStream out, int length) throws IOException {
        return buf.getBytes(index, out, length);
    }

    @Override
    public int getBytes(int index, GatheringByteChannel out, int length) throws IOException {
        return buf.getBytes(index, out, length);
    }

    @Override
    public int getBytes(int index, FileChannel out, long position, int length) throws IOException {
        return buf.getBytes(index, out, position, length);
    }

    @Override
    public CharSequence getCharSequence(int index, int length, Charset charset) {
        return buf.getCharSequence(index, length, charset);
    }

    @Override
    public ByteBuf setBoolean(int index, boolean value) {
        return buf.setBoolean(index, value);
    }

    @Override
    public ByteBuf setByte(int index, int value) {
        return buf.setByte(index, value);
    }

    @Override
    public ByteBuf setShort(int index, int value) {
        return buf.setShort(index, value);
    }

    @Override
    public ByteBuf setShortLE(int index, int value) {
        return buf.setShortLE(index, value);
    }

    @Override
    public ByteBuf setMedium(int index, int value) {
        return buf.setMedium(index, value);
    }

    @Override
    public ByteBuf setMediumLE(int index, int value) {
        return buf.setMediumLE(index, value);
    }

    @Override
    public ByteBuf setInt(int index, int value) {
        return buf.setInt(index, value);
    }

    @Override
    public ByteBuf setIntLE(int index, int value) {
        return buf.setIntLE(index, value);
    }

    @Override
    public ByteBuf setLong(int index, long value) {
        return buf.setLong(index, value);
    }

    @Override
    public ByteBuf setLongLE(int index, long value) {
        return buf.setLongLE(index, value);
    }

    @Override
    public ByteBuf setChar(int index, int value) {
        return buf.setChar(index, value);
    }

    @Override
    public ByteBuf setFloat(int index, float value) {
        return buf.setFloat(index, value);
    }

    @Override
    public ByteBuf setFloatLE(int index, float value) {
        return buf.setFloatLE(index, value);
    }

    @Override
    public ByteBuf setDouble(int index, double value) {
        return buf.setDouble(index, value);
    }

    @Override
    public ByteBuf setDoubleLE(int index, double value) {
        return buf.setDoubleLE(index, value);
    }

    @Override
    public ByteBuf setBytes(int index, ByteBuf src) {
        return buf.setBytes(index, src);
    }

    @Override
    public ByteBuf setBytes(int index, ByteBuf src, int length) {
        return buf.setBytes(index, src, length);
    }

    @Override
    public ByteBuf setBytes(int index, ByteBuf src, int srcIndex, int length) {
        return buf.setBytes(index, src, srcIndex, length);
    }

    @Override
    public ByteBuf setBytes(int index, byte[] src) {
        return buf.setBytes(index, src);
    }

    @Override
    public ByteBuf setBytes(int index, byte[] src, int srcIndex, int length) {
        return buf.setBytes(index, src, srcIndex, length);
    }

    @Override
    public ByteBuf setBytes(int index, ByteBuffer src) {
        return buf.setBytes(index, src);
    }

    @Override
    public int setBytes(int index, InputStream in, int length) throws IOException {
        return buf.setBytes(index, in, length);
    }

    @Override
    public int setBytes(int index, ScatteringByteChannel in, int length) throws IOException {
        return buf.setBytes(index, in, length);
    }

    @Override
    public int setBytes(int index, FileChannel in, long position, int length) throws IOException {
        return buf.setBytes(index, in, position, length);
    }

    @Override
    public ByteBuf setZero(int index, int length) {
        return buf.setZero(index, length);
    }

    @Override
    public int setCharSequence(int index, CharSequence sequence, Charset charset) {
        return buf.setCharSequence(index, sequence, charset);
    }

    @Override
    public boolean readBoolean() {
        return buf.readBoolean();
    }

    @Override
    public byte readByte() {
        return buf.readByte();
    }

    @Override
    public short readUnsignedByte() {
        return buf.readUnsignedByte();
    }

    @Override
    public short readShort() {
        return buf.readShort();
    }

    @Override
    public short readShortLE() {
        return buf.readShortLE();
    }

    @Override
    public int readUnsignedShort() {
        return buf.readUnsignedShort();
    }

    @Override
    public int readUnsignedShortLE() {
        return buf.readUnsignedShortLE();
    }

    @Override
    public int readMedium() {
        return buf.readMedium();
    }

    @Override
    public int readMediumLE() {
        return buf.readMediumLE();
    }

    @Override
    public int readUnsignedMedium() {
        return buf.readUnsignedMedium();
    }

    @Override
    public int readUnsignedMediumLE() {
        return buf.readUnsignedMediumLE();
    }

    @Override
    public int readInt() {
        return buf.readInt();
    }

    @Override
    public int readIntLE() {
        return buf.readIntLE();
    }

    @Override
    public long readUnsignedInt() {
        return buf.readUnsignedInt();
    }

    @Override
    public long readUnsignedIntLE() {
        return buf.readUnsignedIntLE();
    }

    @Override
    public long readLong() {
        return buf.readLong();
    }

    @Override
    public long readLongLE() {
        return buf.readLongLE();
    }

    @Override
    public char readChar() {
        return buf.readChar();
    }

    @Override
    public float readFloat() {
        return buf.readFloat();
    }

    @Override
    public float readFloatLE() {
        return buf.readFloatLE();
    }

    @Override
    public double readDouble() {
        return buf.readDouble();
    }

    @Override
    public double readDoubleLE() {
        return buf.readDoubleLE();
    }

    @Override
    public ByteBuf readBytes(int length) {
        return buf.readBytes(length);
    }

    @Override
    public ByteBuf readSlice(int length) {
        return buf.readSlice(length);
    }

    @Override
    public ByteBuf readRetainedSlice(int length) {
        return buf.readRetainedSlice(length);
    }

    @Override
    public ByteBuf readBytes(ByteBuf dst) {
        return buf.readBytes(dst);
    }

    @Override
    public ByteBuf readBytes(ByteBuf dst, int length) {
        return buf.readBytes(dst, length);
    }

    @Override
    public ByteBuf readBytes(ByteBuf dst, int dstIndex, int length) {
        return buf.readBytes(dst, dstIndex, length);
    }

    @Override
    public ByteBuf readBytes(byte[] dst) {
        return buf.readBytes(dst);
    }

    @Override
    public ByteBuf readBytes(byte[] dst, int dstIndex, int length) {
        return buf.readBytes(dst, dstIndex, length);
    }

    @Override
    public ByteBuf readBytes(ByteBuffer dst) {
        return buf.readBytes(dst);
    }

    @Override
    public ByteBuf readBytes(OutputStream out, int length) throws IOException {
        return buf.readBytes(out, length);
    }

    @Override
    public int readBytes(GatheringByteChannel out, int length) throws IOException {
        return buf.readBytes(out, length);
    }

    @Override
    public CharSequence readCharSequence(int length, Charset charset) {
        return buf.readCharSequence(length, charset);
    }

    @Override
    public int readBytes(FileChannel out, long position, int length) throws IOException {
        return buf.readBytes(out, position, length);
    }

    @Override
    public ByteBuf skipBytes(int length) {
        return buf.skipBytes(length);
    }

    @Override
    public ByteBuf writeBoolean(boolean value) {
        return buf.writeBoolean(value);
    }

    @Override
    public ByteBuf writeByte(int value) {
        return buf.writeByte(value);
    }

    @Override
    public ByteBuf writeShort(int value) {
        return buf.writeShort(value);
    }

    @Override
    public ByteBuf writeShortLE(int value) {
        return buf.writeShortLE(value);
    }

    @Override
    public ByteBuf writeMedium(int value) {
        return buf.writeMedium(value);
    }

    @Override
    public ByteBuf writeMediumLE(int value) {
        return buf.writeMediumLE(value);
    }

    @Override
    public ByteBuf writeInt(int value) {
        return buf.writeInt(value);
    }

    @Override
    public ByteBuf writeIntLE(int value) {
        return buf.writeIntLE(value);
    }

    @Override
    public ByteBuf writeLong(long value) {
        return buf.writeLong(value);
    }

    @Override
    public ByteBuf writeLongLE(long value) {
        return buf.writeLongLE(value);
    }

    @Override
    public ByteBuf writeChar(int value) {
        return buf.writeChar(value);
    }

    @Override
    public ByteBuf writeFloat(float value) {
        return buf.writeFloat(value);
    }

    @Override
    public ByteBuf writeFloatLE(float value) {
        return buf.writeFloatLE(value);
    }

    @Override
    public ByteBuf writeDouble(double value) {
        return buf.writeDouble(value);
    }

    @Override
    public ByteBuf writeDoubleLE(double value) {
        return buf.writeDoubleLE(value);
    }

    @Override
    public ByteBuf writeBytes(ByteBuf src) {
        return buf.writeBytes(src);
    }

    @Override
    public ByteBuf writeBytes(ByteBuf src, int length) {
        return buf.writeBytes(src, length);
    }

    @Override
    public ByteBuf writeBytes(ByteBuf src, int srcIndex, int length) {
        return buf.writeBytes(src, srcIndex, length);
    }

    @Override
    public ByteBuf writeBytes(byte[] src) {
        return buf.writeBytes(src);
    }

    @Override
    public ByteBuf writeBytes(byte[] src, int srcIndex, int length) {
        return buf.writeBytes(src, srcIndex, length);
    }

    @Override
    public ByteBuf writeBytes(ByteBuffer src) {
        return buf.writeBytes(src);
    }

    @Override
    public int writeBytes(InputStream in, int length) throws IOException {
        return buf.writeBytes(in, length);
    }

    @Override
    public int writeBytes(ScatteringByteChannel in, int length) throws IOException {
        return buf.writeBytes(in, length);
    }

    @Override
    public int writeBytes(FileChannel in, long position, int length) throws IOException {
        return buf.writeBytes(in, position, length);
    }

    @Override
    public ByteBuf writeZero(int length) {
        return buf.writeZero(length);
    }

    @Override
    public int writeCharSequence(CharSequence sequence, Charset charset) {
        return buf.writeCharSequence(sequence, charset);
    }

    @Override
    public int indexOf(int fromIndex, int toIndex, byte value) {
        return buf.indexOf(fromIndex, toIndex, value);
    }

    @Override
    public int bytesBefore(byte value) {
        return buf.bytesBefore(value);
    }

    @Override
    public int bytesBefore(int length, byte value) {
        return buf.bytesBefore(length, value);
    }

    @Override
    public int bytesBefore(int index, int length, byte value) {
        return buf.bytesBefore(index, length, value);
    }

    @Override
    public int forEachByte(ByteProcessor processor) {
        return buf.forEachByte(processor);
    }

    @Override
    public int forEachByte(int index, int length, ByteProcessor processor) {
        return buf.forEachByte(index, length, processor);
    }

    @Override
    public int forEachByteDesc(ByteProcessor processor) {
        return buf.forEachByteDesc(processor);
    }

    @Override
    public int forEachByteDesc(int index, int length, ByteProcessor processor) {
        return buf.forEachByteDesc(index, length, processor);
    }

    @Override
    public ByteBuf copy() {
        return buf.copy();
    }

    @Override
    public ByteBuf copy(int index, int length) {
        return buf.copy(index, length);
    }

    @Override
    public ByteBuf slice() {
        return buf.slice();
    }

    @Override
    public ByteBuf retainedSlice() {
        return buf.retainedSlice();
    }

    @Override
    public ByteBuf slice(int index, int length) {
        return buf.slice(index, length);
    }

    @Override
    public ByteBuf retainedSlice(int index, int length) {
        return buf.retainedSlice(index, length);
    }

    @Override
    public ByteBuf duplicate() {
        return buf.duplicate();
    }

    @Override
    public ByteBuf retainedDuplicate() {
        return buf.retainedDuplicate();
    }

    @Override
    public int nioBufferCount() {
        return buf.nioBufferCount();
    }

    @Override
    public ByteBuffer nioBuffer() {
        return buf.nioBuffer();
    }

    @Override
    public ByteBuffer nioBuffer(int index, int length) {
        return buf.nioBuffer(index, length);
    }

    @Override
    public ByteBuffer internalNioBuffer(int index, int length) {
        return buf.internalNioBuffer(index, length);
    }

    @Override
    public ByteBuffer[] nioBuffers() {
        return buf.nioBuffers();
    }

    @Override
    public ByteBuffer[] nioBuffers(int index, int length) {
        return buf.nioBuffers(index, length);
    }

    @Override
    public boolean hasArray() {
        return buf.hasArray();
    }

    @Override
    public byte[] array() {
        return buf.array();
    }

    @Override
    public int arrayOffset() {
        return buf.arrayOffset();
    }

    @Override
    public boolean hasMemoryAddress() {
        return buf.hasMemoryAddress();
    }

    @Override
    public long memoryAddress() {
        return buf.memoryAddress();
    }

    @Override
    public boolean isContiguous() {
        return buf.isContiguous();
    }

    @Override
    public ByteBuf asByteBuf() {
        return buf.asByteBuf();
    }

    @Override
    public String toString(Charset charset) {
        return buf.toString(charset);
    }

    @Override
    public String toString(int index, int length, Charset charset) {
        return buf.toString(index, length, charset);
    }

    @Override
    public int hashCode() {
        return buf.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        return buf.equals(obj);
    }

    @Override
    public int compareTo(ByteBuf buffer) {
        return buf.compareTo(buffer);
    }

    @Override
    public String toString() {
        return buf.toString();
    }

    @Override
    public ByteBuf retain(int increment) {
        return buf.retain(increment);
    }

    @Override
    public ByteBuf retain() {
        return buf.retain();
    }

    @Override
    public ByteBuf touch() {
        return buf.touch();
    }

    @Override
    public ByteBuf touch(Object hint) {
        return buf.touch(hint);
    }

    @Override
    public int refCnt() {
        return buf.refCnt();
    }

    @Override
    public boolean release() {
        return buf.release();
    }

    @Override
    public boolean release(int i) {
        return buf.release(i);
    }
}
