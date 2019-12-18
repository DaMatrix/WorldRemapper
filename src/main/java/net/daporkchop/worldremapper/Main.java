/*
 * Adapted from the Wizardry License
 *
 * Copyright (c) 2019-2019 DaPorkchop_ and contributors
 *
 * Permission is hereby granted to any persons and/or organizations using this software to copy, modify, merge, publish, and distribute it. Said persons and/or organizations are not allowed to use the software or any derivatives of the work for commercial use or any other means to generate income, nor are they allowed to claim this software as their own.
 *
 * The persons and/or organizations are also disallowed from sub-licensing and/or trademarking this software without explicit permission from DaPorkchop_.
 *
 * Any persons and/or organizations using this software must disclose their source code and have it publicly available, include this license, provide sufficient credit to the original authors of the project (IE: DaPorkchop_), as well as provide a link to the original project.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON INFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 */

package net.daporkchop.worldremapper;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import lombok.NonNull;
import net.daporkchop.lib.binary.chars.DirectASCIISequence;
import net.daporkchop.lib.binary.stream.DataIn;
import net.daporkchop.lib.binary.stream.DataOut;
import net.daporkchop.lib.common.function.io.IOConsumer;
import net.daporkchop.lib.common.util.PorkUtil;
import net.daporkchop.lib.logging.LogAmount;
import net.daporkchop.lib.minecraft.world.format.anvil.AnvilPooledNBTArrayAllocator;
import net.daporkchop.lib.minecraft.world.format.anvil.region.RegionConstants;
import net.daporkchop.lib.minecraft.world.format.anvil.region.RegionFile;
import net.daporkchop.lib.minecraft.world.format.anvil.region.RegionOpenOptions;
import net.daporkchop.lib.natives.PNatives;
import net.daporkchop.lib.natives.zlib.PDeflater;
import net.daporkchop.lib.natives.zlib.PInflater;
import net.daporkchop.lib.natives.zlib.Zlib;
import net.daporkchop.lib.nbt.NBTInputStream;
import net.daporkchop.lib.nbt.NBTOutputStream;
import net.daporkchop.lib.nbt.alloc.NBTArrayAllocator;
import net.daporkchop.lib.nbt.tag.notch.CompoundTag;
import net.daporkchop.lib.nbt.tag.notch.ListTag;
import net.daporkchop.lib.primitive.map.IntIntMap;
import net.daporkchop.lib.primitive.map.hash.open.IntIntOpenHashMap;
import sun.nio.ch.DirectBuffer;

import java.io.File;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.daporkchop.lib.logging.Logging.*;

/**
 * @author DaPorkchop_
 */
public class Main {
    protected static final Pattern INPUT_PATTERN  = Pattern.compile("([0-9]+):([0-9]+)(\r?\n|$)");
    protected static final Pattern DIM_PATTERN    = Pattern.compile("^(region|DIM-?[0-9]+)$");
    protected static final Pattern REGION_PATTERN = Pattern.compile("^r\\.(-?[0-9]+)\\.(-?[0-9]+)\\.mca$");

    protected static final NBTArrayAllocator NBT_ALLOC = new AnvilPooledNBTArrayAllocator(16 * 3, 16); //exactly enough to load a full chunk with extended block IDs

    protected static final RegionOpenOptions REGION_OPEN_OPTIONS = new RegionOpenOptions().access(RegionFile.Access.READ_ONLY).mode(RegionFile.Mode.MMAP_FULL);

    protected static final ThreadLocal<PInflater> INFLATER_CACHE = ThreadLocal.withInitial(() -> PNatives.ZLIB.get().inflater(Zlib.ZLIB_MODE_AUTO));
    protected static final ThreadLocal<PDeflater> DEFLATER_CACHE = ThreadLocal.withInitial(() -> PNatives.ZLIB.get().deflater(4));

    protected static final IntIntMap ID_REMAPPERS = new IntIntOpenHashMap();

    public static void main(String... args) {
        logger.enableANSI().setLogAmount(LogAmount.DEBUG);

        Thread.currentThread().setUncaughtExceptionHandler((thread, ex) -> {
            logger.alert(ex);
            System.exit(1);
        });

        if (!PNatives.ZLIB.isNative()) {
            throw new IllegalStateException("Native zlib couldn't be loaded! Only supported on x86_64-linux-gnu, x86-linux-gnu and x86_64-w64-mingw32");
        }

        {
            //load input data
            //holy cow this is fast
            MappedByteBuffer buffer;
            try (FileChannel channel = FileChannel.open(new File(args[1]).toPath(), StandardOpenOption.READ)) {
                buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0L, channel.size());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            Matcher matcher = INPUT_PATTERN.matcher(new DirectASCIISequence(((DirectBuffer) buffer).address(), buffer.capacity()));
            while (matcher.find()) {
                int from = Integer.parseInt(matcher.group(1));
                int to = Integer.parseInt(matcher.group(2));
                if (from == to) {
                    continue;
                } else if (ID_REMAPPERS.containsKey(from)) {
                    logger.alert("ID %d is already being mapped to %d (duplicate entry attempts to map it to %d)", from, ID_REMAPPERS.get(from), to);
                    return;
                } else {
                    ID_REMAPPERS.put(from, to);
                }
            }
            PorkUtil.release(buffer);
            logger.info("Preparing to remap %d map IDs...", ID_REMAPPERS.size());
        }

        File rootDir = new File(args[0]).getAbsoluteFile();
        if (!rootDir.exists()) {
            throw new IllegalArgumentException(rootDir.getAbsolutePath() + " does not exist!");
        } else if (!rootDir.isDirectory()) {
            throw new IllegalArgumentException(rootDir.getAbsolutePath() + " is not a directory!");
        }

        File[] dims = rootDir.listFiles((file, name) -> DIM_PATTERN.matcher(name).matches());
        if (dims == null) throw new NullPointerException("dims");
        logger.info("Processing %d dimensions...", dims.length);

        for (File dim : dims) {
            if (!"region".equals(dim.getName())) {
                dim = new File(dim, "region");
                if (!dim.exists() || !dim.isDirectory()) {
                    logger.info("Skipping invalid dimension %s...", dim.getParentFile());
                    continue;
                }
            }
            File[] regions = dim.listFiles((file, name) -> REGION_PATTERN.matcher(name).matches());
            if (regions == null) throw new NullPointerException("regions");
            logger.info("Processing dimension %s, with %d regions...", dim, regions.length);

            Arrays.stream(regions).parallel().forEach((IOConsumer<File>) file -> {
                logger.trace("Processing region %s...", file);

                ByteBuf out = PooledByteBufAllocator.DEFAULT.ioBuffer(RegionConstants.SECTOR_BYTES * (2 + 32 * 32));
                out.writeBytes(RegionConstants.EMPTY_SECTOR).writeBytes(RegionConstants.EMPTY_SECTOR);
                boolean dirty = false;
                try {
                    ByteBuf inflatedChunk = PooledByteBufAllocator.DEFAULT.ioBuffer(2097152); //2 MiB
                    try (RegionFile region = RegionFile.open(file, REGION_OPEN_OPTIONS)) {
                        PInflater inflater = INFLATER_CACHE.get();
                        PDeflater deflater = DEFLATER_CACHE.get();
                        int sector = 2;
                        for (int x = 31; x >= 0; x--) {
                            for (int z = 31; z >= 0; z--) {
                                if (!region.hasChunk(x, z)) {
                                    continue;
                                }
                                inflatedChunk.clear();
                                ByteBuf chunk = region.readDirect(x, z).markReaderIndex().skipBytes(1);
                                try {
                                    inflater.inflate(chunk, inflatedChunk);
                                    inflater.reset();

                                    CompoundTag tag;
                                    try (NBTInputStream nbt = new NBTInputStream(DataIn.wrap(inflatedChunk), NBT_ALLOC)) {
                                        tag = nbt.readTag();
                                    }

                                    if (processChunk(tag)) {
                                        //if true, the tag was modified
                                        dirty = true;
                                        final int oldIndex = out.writerIndex();
                                        out.writeInt(-1);
                                        out.writeByte(RegionConstants.ID_ZLIB);

                                        //re-encode chunk to inflatedChunk buf
                                        inflatedChunk.clear();
                                        try (NBTOutputStream nbt = new NBTOutputStream(DataOut.wrap(inflatedChunk))) {
                                            nbt.writeTag(tag);
                                        }

                                        //re-compress chunk directly to output buffer
                                        deflater.deflate(inflatedChunk, out);
                                        deflater.reset();

                                        //update chunk length in bytes now that it is known
                                        out.setInt(oldIndex, out.writerIndex() - oldIndex - 4);
                                        logger.info("New size: %d bytes", out.writerIndex() - oldIndex - 4);
                                    } else {
                                        //simply write compressed chunk straight to output buffer
                                        out.writeInt(chunk.resetReaderIndex().readableBytes());
                                        out.writeBytes(chunk);
                                    }

                                    //pad chunk
                                    out.writeBytes(RegionConstants.EMPTY_SECTOR, 0, ((out.writerIndex() - 1 >> 12) + 1 << 12) - out.writerIndex());

                                    final int chunkSectors = (out.writerIndex() - 1 >> 12) + 1;
                                    out.setInt(RegionConstants.getOffsetIndex(x, z), (chunkSectors - sector) | (sector << 8));
                                    sector = chunkSectors;

                                    out.setInt(RegionConstants.getTimestampIndex(x, z), (int) (System.currentTimeMillis() / 1000L));
                                } finally {
                                    if (chunk != null) {
                                        chunk.release();
                                    }
                                }
                            }
                        }
                    } finally {
                        inflatedChunk.release();
                    }
                    if (dirty) {
                        logger.debug("Region %s was modified, overwriting...", file);
                        //write region again if dirty
                        try (FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                            out.readBytes(channel, out.readableBytes());
                            if (out.isReadable()) {
                                throw new IllegalStateException("Unable to write whole region!");
                            }
                        }
                    }
                } finally {
                    out.release();
                }
            });
        }
    }

    protected static boolean processChunk(@NonNull CompoundTag chunk) {
        boolean dirty = false;
        {
            //iterate over entities to find item frames
            ListTag<CompoundTag> entities = chunk.getCompound("Level").getList("Entities");
            for (int i = 0, size = entities.size(); i < size; i++) {
                CompoundTag entity = entities.get(i);
                if ("minecraft:item_frame".equals(entity.getString("id"))) {
                    if (processItem(entity.getCompound("Item")))   {
                        dirty = true;
                    }
                }
            }
        }
        {
            ListTag<CompoundTag> tileEntities = chunk.getCompound("Level").getList("TileEntities");
            for (int i = 0, size = tileEntities.size(); i < size; i++) {
                ListTag<CompoundTag> items = tileEntities.get(i).getList("Items");
                if (items == null)  {
                    //tile entity does not have an inventory
                    continue;
                } else {
                    for (int j = 0, itemsSize = items.size(); j < itemsSize; j++)   {
                        if (processItem(items.get(j)))  {
                            dirty = true;
                        }
                    }
                }
            }
        }
        return dirty;
    }

    protected static boolean processItem(CompoundTag item) {
        if (item != null && "minecraft:filled_map".equals(item.getString("id"))) {
            int toId = ID_REMAPPERS.get(item.getShort("Damage") & 0xFFFF);
            if (toId != Integer.MIN_VALUE) {
                //logger.info("Found map id=%d!", item.getShort("Damage"));
                item.putShort("Damage", (short) toId);
                return true;
            }
        }
        return false;
    }
}
