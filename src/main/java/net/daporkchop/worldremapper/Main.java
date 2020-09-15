/*
 * Adapted from the Wizardry License
 *
 * Copyright (c) 2019-2020 DaPorkchop_ and contributors
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
import net.daporkchop.lib.compression.PDeflater;
import net.daporkchop.lib.compression.PInflater;
import net.daporkchop.lib.compression.zlib.Zlib;
import net.daporkchop.lib.logging.LogAmount;
import net.daporkchop.lib.minecraft.util.SectionLayer;
import net.daporkchop.lib.minecraft.world.format.anvil.AnvilPooledNBTArrayAllocator;
import net.daporkchop.lib.minecraft.world.format.anvil.region.RegionConstants;
import net.daporkchop.lib.minecraft.world.format.anvil.region.RegionFile;
import net.daporkchop.lib.minecraft.world.format.anvil.region.RegionOpenOptions;
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
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.daporkchop.lib.logging.Logging.*;

/**
 * @author DaPorkchop_
 */
public class Main {
    protected static final Pattern DIM_PATTERN = Pattern.compile("^(region|DIM-?[0-9]+)$");
    protected static final Pattern REGION_PATTERN = Pattern.compile("^r\\.(-?[0-9]+)\\.(-?[0-9]+)\\.mca$");

    protected static final NBTArrayAllocator NBT_ALLOC = new AnvilPooledNBTArrayAllocator(16 * 3, 16); //exactly enough to load a full chunk with extended block IDs

    protected static final RegionOpenOptions REGION_OPEN_OPTIONS = new RegionOpenOptions().access(RegionFile.Access.READ_ONLY).mode(RegionFile.Mode.MMAP_FULL);

    protected static final ThreadLocal<PInflater> INFLATER_CACHE = ThreadLocal.withInitial(Zlib.PROVIDER::inflaterAuto);
    protected static final ThreadLocal<PDeflater> DEFLATER_CACHE = ThreadLocal.withInitial(Zlib.PROVIDER::deflater);

    public static void main(String... args) {
        logger.enableANSI().setLogAmount(LogAmount.DEBUG);

        Thread.currentThread().setUncaughtExceptionHandler((thread, ex) -> {
            logger.alert(ex);
            System.exit(1);
        });

        if (!Zlib.PROVIDER.isNative()) {
            throw new IllegalStateException("Native zlib couldn't be loaded! Only supported on x86_64-linux-gnu, x86-linux-gnu and x86_64-w64-mingw32");
        }

        File rootDir = new File(args[0]).getAbsoluteFile();
        if (!rootDir.exists()) {
            throw new IllegalArgumentException(rootDir.getAbsolutePath() + " does not exist!");
        } else if (!rootDir.isDirectory()) {
            throw new IllegalArgumentException(rootDir.getAbsolutePath() + " is not a directory!");
        }

        File[] dims = rootDir.listFiles((file, name) -> DIM_PATTERN.matcher(name).matches());
        if (dims == null) {
            throw new NullPointerException("dims");
        }
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
            if (regions == null) {
                throw new NullPointerException("regions");
            }
            logger.info("Processing dimension %s, with %d regions...", dim, regions.length);

            Arrays.stream(regions).parallel().forEach((IOConsumer<File>) file -> {
                logger.trace("Processing region %s...", file);

                ByteBuf out = PooledByteBufAllocator.DEFAULT.ioBuffer(RegionConstants.SECTOR_BYTES * (2 + 32 * 32))
                        .writeBytes(RegionConstants.EMPTY_SECTOR)
                        .writeBytes(RegionConstants.EMPTY_SECTOR);
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
                                    inflater.fullInflateGrowing(chunk, inflatedChunk);
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
                                        deflater.fullDeflateGrowing(inflatedChunk, out);
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

                                    //release tag to allow array reuse
                                    tag.release();
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

        ListTag<CompoundTag> sections = chunk.getCompound("Level").getList("Sections");
        for (int i = 0, size = sections.size(); i < size; i++) {
            CompoundTag section = sections.get(i);
            if (section.getByte("Y") == 15) {
                byte[] blocks = section.getByteArray("Blocks");
                SectionLayer meta = new SectionLayer(section.getByteArray("Data"));
                for (int x = 0; x < 15; x++)    {
                    for (int z = 0; z < 15; z++){
                        int index = (0xF << 8) | (z << 4) | x;
                        if (blocks[index] != 0 || meta.get(x, 15, z) != 0) {
                            blocks[index] = (byte) 0;
                            meta.set(x, 15, z, 0);
                            dirty = true;
                        }
                    }
                }
            }
        }
        return dirty;
    }
}
