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
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.Unpooled;
import net.daporkchop.lib.binary.netty.PUnpooled;
import net.daporkchop.lib.logging.LogAmount;
import net.daporkchop.lib.logging.console.ansi.ANSIMessagePrinter;
import net.daporkchop.lib.natives.PNatives;
import net.daporkchop.lib.natives.zlib.PDeflater;
import net.daporkchop.lib.natives.zlib.PInflater;
import net.daporkchop.lib.natives.zlib.Zlib;
import net.daporkchop.lib.nbt.NBTInputStream;
import net.daporkchop.lib.nbt.NBTOutputStream;
import net.daporkchop.lib.nbt.tag.notch.CompoundTag;
import net.daporkchop.lib.nbt.tag.notch.DoubleTag;
import net.daporkchop.lib.nbt.tag.notch.FloatTag;
import net.daporkchop.lib.nbt.tag.notch.IntTag;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Collections;

import static net.daporkchop.lib.logging.Logging.*;

/**
 * @author DaPorkchop_
 */
public class Main {
    public static void main(String... args) throws IOException {
        logger.setLogAmount(LogAmount.DEBUG);
        logger.setDelegate("console", logger.getLogLevels(), new ANSIMessagePrinter());
        logger.info("Enabling ANSI formatting manually :)");

        Thread.currentThread().setUncaughtExceptionHandler((thread, ex) -> {
            logger.alert(ex);
            System.exit(1);
        });

        if (!PNatives.ZLIB.isNative()) {
            throw new IllegalStateException("Native zlib couldn't be loaded! Only supported on x86_64-linux-gnu, x86-linux-gnu and x86_64-w64-mingw32");
        }

        ByteBuf uncompressed = Unpooled.directBuffer();
        try (FileChannel channel = FileChannel.open(new File(args[0]).toPath(), StandardOpenOption.READ);
             PInflater inflater = PNatives.ZLIB.get().inflater(Zlib.ZLIB_MODE_GZIP)) {
            ByteBuf map = PUnpooled.wrap(channel.map(FileChannel.MapMode.READ_ONLY, 0L, channel.size()), true);
            try {
                inflater.inflate(map, uncompressed);
            } finally {
                map.release();
            }
        }
        logger.info("Loaded NBT!");

        CompoundTag tag = new NBTInputStream(new ByteBufInputStream(uncompressed)).readTag();
        CompoundTag dataTag = tag.getCompound("Data");
        CompoundTag playerTag = dataTag.getCompound("Player");
        logger.info("Parsed NBT!");

        dataTag.putInt("clearWeatherTime", 3);
        dataTag.putByte("Difficulty", (byte) 3);
        dataTag.putInt("GameType", 0);
        dataTag.putString("generatorName", "flat");
        dataTag.putString("generatorOptions", ";0");
        dataTag.putByte("MapFeatures", (byte) 0);
        dataTag.putByte("raining", (byte) 0);
        dataTag.putInt("thunderTime", Integer.MAX_VALUE - 1);
        dataTag.putInt("rainTime", Integer.MAX_VALUE - 1);
        dataTag.putInt("clearWeatherTime", Integer.MAX_VALUE - 1);

        playerTag.putList("EnderItems", Collections.emptyList());
        playerTag.putList("Inventory", Collections.emptyList());

        playerTag.remove("ForgeDataVersion");
        dataTag.remove("forge");
        tag.remove("forge");

        playerTag.putList("Pos", Arrays.asList(
                new DoubleTag("", Double.parseDouble(args[1])),
                new DoubleTag("", Double.parseDouble(args[2])),
                new DoubleTag("", Double.parseDouble(args[3]))
        ));

        dataTag.putInt("SpawnX", Integer.parseInt(args[4]));
        dataTag.putInt("SpawnY", Integer.parseInt(args[5]));
        dataTag.putInt("SpawnZ", Integer.parseInt(args[6]));

        playerTag.putList("Rotation", Arrays.asList(
                new FloatTag("", Float.parseFloat(args[7])),
                new FloatTag("", Float.parseFloat(args[8]))
        ));

        dataTag.putLong("LastPlayed", Long.parseUnsignedLong(args[9]));
        dataTag.putLong("RandomSeed", Long.parseLong(args[10]));
        dataTag.putString("LevelName", args[11]);

        new NBTOutputStream(new ByteBufOutputStream(uncompressed.clear())).writeTag(tag);
        ByteBuf compressed = Unpooled.directBuffer();
        try (PDeflater deflater = PNatives.ZLIB.get().deflater(Zlib.ZLIB_LEVEL_BEST, Zlib.ZLIB_MODE_GZIP))  {
            deflater.deflate(uncompressed, compressed);
        }

        try (FileChannel channel = FileChannel.open(new File(args[0]).toPath(), StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            compressed.readBytes(channel, 0L, compressed.readableBytes());
            if (compressed.isReadable())    {
                throw new IllegalStateException(String.valueOf(compressed.readableBytes()));
            }
        }
    }
}
