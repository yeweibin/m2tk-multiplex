/*
 * Copyright (c) M2TK Project. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package m2tk.multiplex;

import m2tk.encoding.Encoding;
import m2tk.mpeg2.MPEG2;
import m2tk.mpeg2.ProgramClockReference;
import m2tk.mpeg2.decoder.TransportPacketDecoder;
import m2tk.mpeg2.decoder.element.AdaptationFieldDecoder;
import m2tk.mpeg2.decoder.element.ProgramClockReferenceDecoder;

import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Objects;

public class TSFileProbe
{
    private final byte[] buffer;
    private int buffer_limit;
    private int buffer_position;
    private int frame_size;
    private int first_sync_point;
    private long length;
    private int bitrate;
    private RandomAccessFile file;

    public TSFileProbe()
    {
        this(new byte[16 * 1024]); // make a 16k cache buffer
    }

    public TSFileProbe(byte[] cache)
    {
        buffer = Objects.requireNonNull(cache);
        frame_size = -1; // unknown
        first_sync_point = -1; // unknown
        length = -1; // unknown
        bitrate = -1; // unknown
    }

    public int getFrameSize()
    {
        return frame_size;
    }

    public int getFirstSyncPoint()
    {
        return first_sync_point;
    }

    public long getLength()
    {
        return length;
    }

    public int getBitrate()
    {
        return bitrate;
    }

    public void probe(String target)
    {
        try (RandomAccessFile raf = new RandomAccessFile(target, "r"))
        {
            file = raf;
            length = file.length();
            frame_size = -1;
            first_sync_point = -1;
            bitrate = -1;

            cache();

            // 获取帧大小（188|204）
            // 获取同步起始点
            // 尝试帧长为 188 字节
            frame_size = 188;
            first_sync_point = (int) seek_sync_point();
            if (first_sync_point >= 0)
            {
                // Synchronized
                probe_bitrate();
                return;
            }

            // 重置文件位置
            file.seek(0);
            cache();

            // 尝试帧长为 204 字节
            frame_size = 204;
            first_sync_point = (int) seek_sync_point();
            if (first_sync_point >= 0)
            {
                // Synchronized
                probe_bitrate();
                return;
            }

            // 帧长未知，或者码流无法同步，
            // 亦或者，同步点离文件起始点太远，导致int溢出（文件质量太差）。
            frame_size = -1;
            first_sync_point = -1;
            bitrate = -1;
        } catch (Exception ex)
        {
            frame_size = -1;
            first_sync_point = -1;
            bitrate = -1;
        }
    }

    private boolean cache() throws IOException
    {
        buffer_position = 0;
        buffer_limit = file.read(buffer);
        return buffer_limit > 0;
    }

    private long seek_sync_point() throws IOException
    {
        // 同步点记录的是文件位置，而不是缓存位置。
        // 通常情况下，应该从文件头开始寻找同步点，但也会应为码流中的同步丢失而重新同步。因此
        // 对于重同步而达到文件末尾时，直接回到文件最初的同步点。
        // 同步算法遵从 ISO.13818-1 的相关规定，取连续5个同步字节的第一个作为同步点。
        // 返回 -1 表示没有找到同步点。

        int count = 0;
        int skips = frame_size - 1;
        long sync_point = -1;

        while (count < 5)
        {
            byte b;
            if (buffer_remaining() > 0 || cache())
            {
                b = buffer[buffer_position];
                buffer_position += 1;
            } else
            {
                return -1; // EOF || IOE
            }

            if (b != 0x47)
            {
                if (sync_point != -1)
                {
                    // 此处应该出现同步字节，但 sync_byte 不等于 0x47，
                    // 所以认为上一个同步点无效。回溯到上一个同步点的后一字节。
                    file.seek(sync_point + 1);
                    cache();
                    sync_point = -1;
                    count = 0;
                }

                // 此时尚未同步，继续循环，逐字节寻找同步字节。
                continue;
            }

            // 找到“疑似”同步字节
            if (count == 0)
            {
                // 标记“疑似”同步点
                sync_point = file.getFilePointer() - buffer_remaining() - 1;
            }

            // 缓存区数据充足，直接滑向下一个同步字节位置
            if (buffer_remaining() >= skips)
            {
                buffer_position += skips;
                count++;
                continue;
            }

            // 缓存区数据不足，需要额外缓存。
            skips -= buffer_remaining();
            if (!cache() || buffer_remaining() < skips)
            {
                return -1; // EOF || IOE
            }

            buffer_position += skips;
            count++;
        }

        return sync_point;
    }

    private int buffer_remaining()
    {
        return buffer_limit - buffer_position;
    }

    private void probe_bitrate()
    {
        byte[] pktbuf = new byte[frame_size];
        Encoding packet = Encoding.wrap(pktbuf);

        TransportPacketDecoder tsd = new TransportPacketDecoder();
        AdaptationFieldDecoder afd = new AdaptationFieldDecoder();
        ProgramClockReferenceDecoder pcrd = new ProgramClockReferenceDecoder();

        tsd.attach(packet);

        long pct = 0;
        int pcr_pid = -1;
        long last_pcr = -1;
        boolean sync_byte_error = false;

        try
        {
            file.seek(first_sync_point);
            cache();

            while (true)
            {
                read_one_packet(pktbuf);
                pct++;

                if (tsd.getSyncByte() != MPEG2.TS_SYNC_BYTE)
                {
                    if (!sync_byte_error)
                    {
                        // 第一次同步字节出现错误，暂不认为同步丢失，但丢弃当前包。
                        sync_byte_error = true;
                    } else
                    {
                        // 第二次同步字节出现错误，认为同步丢失，需要重新同步
                        sync_byte_error = false;
                        long sync_point = seek_sync_point();
                        if (sync_point == -1)
                            return;

                        file.seek(sync_point);
                        cache();

                        last_pcr = -1;
                        pct = 0;
                    }
                } else
                {
                    if ((pcr_pid != -1 && pcr_pid != tsd.getPID()) ||
                        !tsd.containsUsefulAdaptationField())
                        continue;

                    // has adaptation_field
                    long pcr = find_pcr_in_adaptation_field(tsd, afd, pcrd);
                    if (pcr == -1)
                        continue; // no pcr

                    // 遇到的第一个PCR
                    if (pcr_pid == -1)
                    {
                        pcr_pid = tsd.getPID();
                        last_pcr = pcr;
                        pct = 1;
                        continue;
                    }

                    // 重同步后第一次收到PCR
                    if (last_pcr == -1)
                    {
                        last_pcr = pcr;
                        pct = 1;
                        continue;
                    }

                    bitrate = ProgramClockReference.bitrate(last_pcr, pcr, pct);
                    return;
                }
            } // while最终一定会被EOF打断
        } catch (IOException ex)
        {
            // 读到文件末尾，或其他IO异常。
        }
    }

    private void read_one_packet(byte[] packet) throws IOException
    {
        int toRead = frame_size;
        int offset = 0;
        while (toRead > 0)
        {
            int available = Math.min(toRead, buffer_remaining());
            if (available > 0)
            {
                System.arraycopy(buffer, buffer_position, packet, offset, available);
                offset += available;
                toRead -= available;
                buffer_position += available;
            } else
            {
                if (!cache())
                    throw new EOFException();
            }
        }
    }

    private long find_pcr_in_adaptation_field(TransportPacketDecoder packet,
                                              AdaptationFieldDecoder adaptationField,
                                              ProgramClockReferenceDecoder pcr)
    {
        try
        {
            adaptationField.attach(packet.getAdaptationField());
            if (adaptationField.getProgramClockReferenceFlag() == 0)
                return -1;

            pcr.attach(adaptationField.getProgramClockReference());
            return pcr.getProgramClockReferenceValue();
        } catch (Exception ex)
        {
            return -1;
        }
    }
}
