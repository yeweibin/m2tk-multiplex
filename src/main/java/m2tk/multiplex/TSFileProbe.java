/*
 * Copyright (c) Ye Weibin. All rights reserved.
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

            // ??????????????????188|204???
            // ?????????????????????
            // ??????????????? 188 ??????
            frame_size = 188;
            first_sync_point = (int) seek_sync_point();
            if (first_sync_point >= 0)
            {
                // Synchronized
                probe_bitrate();
                return;
            }

            // ??????????????????
            file.seek(0);
            cache();

            // ??????????????? 204 ??????
            frame_size = 204;
            first_sync_point = (int) seek_sync_point();
            if (first_sync_point >= 0)
            {
                // Synchronized
                probe_bitrate();
                return;
            }

            // ??????????????????????????????????????????
            // ??????????????????????????????????????????????????????int?????????????????????????????????
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
        // ????????????????????????????????????????????????????????????
        // ???????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
        // ?????????????????????????????????????????????????????????????????????????????????
        // ?????????????????? ISO.13818-1 ???????????????????????????5?????????????????????????????????????????????
        // ?????? -1 ??????????????????????????????

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
                    // ???????????????????????????????????? sync_byte ????????? 0x47???
                    // ????????????????????????????????????????????????????????????????????????????????????
                    file.seek(sync_point + 1);
                    cache();
                    sync_point = -1;
                    count = 0;
                }

                // ??????????????????????????????????????????????????????????????????
                continue;
            }

            // ??????????????????????????????
            if (count == 0)
            {
                // ???????????????????????????
                sync_point = file.getFilePointer() - buffer_remaining() - 1;
            }

            // ???????????????????????????????????????????????????????????????
            if (buffer_remaining() >= skips)
            {
                buffer_position += skips;
                count++;
                continue;
            }

            // ?????????????????????????????????????????????
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

    private long buffer_remaining()
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
                        // ????????????????????????????????????????????????????????????????????????????????????
                        sync_byte_error = true;
                    } else
                    {
                        // ???????????????????????????????????????????????????????????????????????????
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

                    // ??????????????????PCR
                    if (pcr_pid == -1)
                    {
                        pcr_pid = tsd.getPID();
                        last_pcr = pcr;
                        pct = 1;
                        continue;
                    }

                    // ???????????????????????????PCR
                    if (last_pcr == -1)
                    {
                        last_pcr = pcr;
                        pct = 1;
                        continue;
                    }

                    bitrate = ProgramClockReference.bitrate(last_pcr, pcr, pct);
                    return;
                }
            } // while??????????????????EOF??????
        } catch (IOException ex)
        {
            // ??????????????????????????????IO?????????
        }
    }

    private void read_one_packet(byte[] packet) throws IOException
    {
        int toRead = frame_size;
        int offset = 0;
        while (toRead > 0)
        {
            int available = Math.min(toRead, (int) buffer_remaining());
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
