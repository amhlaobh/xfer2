/**
 * Output stream that compresses data. A compressed block
 * is generated and transmitted once a given number of bytes
 * have been written, or when the flush method is invoked.
 *
 * Copyright 2005 - Philip Isenhour - http://javatechniques.com/
 *
 * This software is provided 'as-is', without any express or
 * implied warranty. In no event will the authors be held liable
 * for any damages arising from the use of this software.
 *
 * Permission is granted to anyone to use this software for any
 * purpose, including commercial applications, and to alter it and
 * redistribute it freely, subject to the following restrictions:
 *
 *  1. The origin of this software must not be misrepresented; you
 *     must not claim that you wrote the original software. If you
 *     use this software in a product, an acknowledgment in the
 *     product documentation would be appreciated but is not required.
 *
 *  2. Altered source versions must be plainly marked as such, and
 *     must not be misrepresented as being the original software.
 *
 *  3. This notice may not be removed or altered from any source
 *     distribution.
 *
 * $Id:  1.1 2005/10/26 17:19:05 isenhour Exp $
 * 
 * http://javatechniques.com/blog/compressing-data-sent-over-a-socket/
 * 
 * 1. The streams’ compression functionality should be invisible to the application.
 *    In other words, it should be possible to simply wrap them around the socket’s
 *    input and output streams, then write to and read from them without having to 
 *    know when a block of data should be compressed or decompressed.
 * 2. A compressed block of data should be sent whenever a given number of bytes 
 *    have been written. This will prevent OutOfMemoryErrors from happening if a very 
 *    large block of data is written to the stream.
 * 3. The flush method in CompressedBlockOutputStream should cause any buffered 
 *    input to be immediately compressed and transmitted, even if the size threshold
 *    has not been reached yet.
 * 4. Stream meta-data (beyond any header and trailer information used by ZLIB) 
 *    should be kept to a minimum. In practice, the implementation uses 8 bytes of 
 *    header information: One 4-byte integer that indicates the size of the block 
 *    of the compressed data and another 4-byte integer that indicates the size of 
 *    the uncompressed data.
 * 
 * The classes are not thread-safe. Synchronization will be needed if, 
 * for example, multiple threads will be writing to a CompressedBlockOutputStream.
 * 
 * Any buffered data will be sent when the CompressedBlockOutputStream is flushed 
 * or closed. If you need to ensure that buffered data is always sent when the 
 * program terminates, you can use Runtime’s addShutdownHook method to register
 * a thread that will flush the stream even if the program exits uncleanly.
 * 
 * Generally speaking, you will get higher compression levels for larger blocks 
 * of data, since the compression algorithm will have more opportunities to find 
 * patterns and redundancies. The tradeoff, of course, is that larger buffer sizes 
 * increase memory usage and latency.
 *
 * ------
 *  modified by amhlaobh@users.noreply.github.com

 */
import java.io.*;
import java.util.zip.Deflater;

public class CompressedBlockOutputStream extends FilterOutputStream {
    /**
     * Buffer for input data
     */
    private byte[] inBuf = null;

    /**
     * Buffer for compressed data to be written
     */
    private byte[] outBuf = null;

    /**
     * Number of bytes in the buffer
     */
    private int len = 0;

    /**
     * Deflater for compressing data
     */
    private Deflater deflater = null;
    
    //vvv added amhlaobh@users.noreply.github.com
    public CompressedBlockOutputStream(OutputStream os) throws IOException {
        this(os, 1024*16);
    }
    //^^^

    /**
     * Constructs a CompressedBlockOutputStream that writes to
     * the given underlying output stream 'os' and sends a compressed
     * block once 'size' byte have been written. The default
     * compression strategy and level are used.
     */
    public CompressedBlockOutputStream(OutputStream os, int size)
        throws IOException {
        this(os, size,
            Deflater.DEFAULT_COMPRESSION, Deflater.DEFAULT_STRATEGY);
    }

    /**
     * Constructs a CompressedBlockOutputStream that writes to the
     * given underlying output stream 'os' and sends a compressed
     * block once 'size' byte have been written. The compression
     * level and strategy should be specified using the constants
     * defined in java.util.zip.Deflator.
     */
    public CompressedBlockOutputStream(OutputStream os, int size,
        int level, int strategy) throws IOException {
        super(os);
        this.inBuf = new byte[size];
        this.outBuf = new byte[size + 64];
        this.deflater = new Deflater(level);
        this.deflater.setStrategy(strategy);
    }

    protected void compressAndSend() throws IOException {
        //vvv added amhlaobh@users.noreply.github.com
        if (len > inBuf.length){
            System.err.println("ArrayIndexOutOfBoundsException: len="+len+"   inBuf.length="+inBuf.length);
            //return;
        }
        //^^^
        if (len > 0) {
            deflater.setInput(inBuf, 0, len);
            deflater.finish();
            int size = deflater.deflate(outBuf);

            // Write the size of the compressed data, followed
            // by the size of the uncompressed data
            out.write((size >> 24) & 0xFF);
            out.write((size >> 16) & 0xFF);
            out.write((size >>  8) & 0xFF);
            out.write((size >>  0) & 0xFF);

            out.write((len >> 24) & 0xFF);
            out.write((len >> 16) & 0xFF);
            out.write((len >>  8) & 0xFF);
            out.write((len >>  0) & 0xFF);

            out.write(outBuf, 0, size);
            out.flush();

            len = 0;
            deflater.reset();
        }
    }

    public void write(int b) throws IOException {
        //modified amhlaobh@users.noreply.github.com
        //both write methods change len, potentially causing an
        //ArrayIndexOutOfBoundsException, therefore forward to other write
        //method
        //vvv
        write(new byte[]{(byte)b});
        /*
        inBuf[len++] = (byte) b;
        if (len == inBuf.length) {
            compressAndSend();
        }
        */
        //^^^
    }

    public void write(byte[] b, int boff, int blen)
        throws IOException {
        while ((len + blen) > inBuf.length) {
            int toCopy = inBuf.length - len;
            System.arraycopy(b, boff, inBuf, len, toCopy);
            len += toCopy;
            compressAndSend();
            boff += toCopy;
            blen -= toCopy;
        }
        System.arraycopy(b, boff, inBuf, len, blen);
        len += blen;
    }

    public void flush() throws IOException {
        compressAndSend();
        out.flush();
    }

    public void close() throws IOException {
        compressAndSend();
        out.close();
    }
}
