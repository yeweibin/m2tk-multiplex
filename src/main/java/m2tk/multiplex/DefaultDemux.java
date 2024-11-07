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
import m2tk.io.RxChannel;
import m2tk.mpeg2.MPEG2;
import m2tk.util.BigEndian;

import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * A default implementation of TSDemux
 */
class DefaultDemux implements TSDemux
{
    private static final int ALLOW_DUPLICATE_PACKET = 0;
    private static final int NOT_ALLOW_DUPLICATE_PACKET = 1;
    private static final byte[] DUPLICATE_PACKET_MASK = new byte[MPEG2.TS_PACKET_SIZE];

    static
    {
        Arrays.fill(DUPLICATE_PACKET_MASK, (byte) 0xFF);
        Arrays.fill(DUPLICATE_PACKET_MASK, 6, 12, (byte) 0); // PCR field
    }

    private final List<DemuxChannel> channels;
    private final List<Consumer<TSDemuxEvent>> listeners;
    private final ExecutorService executor;

    private Future<?> task;
    private volatile boolean stop_reading;

    /*
     * 单线程模型
     * 接收线程负责处理每个传输包，监视和通知流状态变化，转发被关注的包和负载
     * （在此线程中直接向所有目标处理器转发数据包）。
     *
     */
    DefaultDemux(ExecutorService runner)
    {
        channels = new CopyOnWriteArrayList<>();
        listeners = new CopyOnWriteArrayList<>();

        executor = runner;
        task = null;
        stop_reading = true;
    }

    @Override
    public synchronized void attach(RxChannel source)
    {
        if (task != null && !task.isDone())
            throw new IllegalStateException("There is a source not been detached yet.");

        task = executor.submit(new DemuxRoutine(source));
    }

    @Override
    public synchronized void attach(RxChannel source, int frameSize, int syncPoint)
    {
        if (task != null && !task.isDone())
            throw new IllegalStateException("There is a source not been detached yet.");

        task = executor.submit(new DemuxRoutine(source, frameSize, syncPoint));
    }

    @Override
    public synchronized void detach()
    {
        stop_reading = true;
    }

    @Override
    public synchronized void join()
    {
        if (task == null || task.isDone())
            return;

        try
        {
            task.get();
        } catch (ExecutionException | InterruptedException ex)
        {
            System.err.println("demux routine interrupted: " + ex.getMessage());
            Thread.currentThread().interrupt();
        }

        task = null;
    }

    @Override
    public void shutdown()
    {
        detach();
        join();
        channels.clear();
    }

    @Override
    public boolean registerEventListener(Consumer<TSDemuxEvent> listener)
    {
        return listeners.add(listener);
    }

    @Override
    public boolean unregisterEventListener(Consumer<TSDemuxEvent> listener)
    {
        return listeners.remove(listener);
    }

    @Override
    public void reset()
    {
        if (!stop_reading)
            throw new IllegalStateException("解复用器正在工作。");

        channels.clear();
        listeners.clear();
    }

    @Override
    public TSDemux.Channel requestChannel(TSDemuxPayload.Type type)
    {
        DemuxChannel channel;

        switch (type)
        {
            case RAW:
                channel = new RawChannel();
                break;
            case PES:
                channel = new PESChannel();
                break;
            case SECTION:
                channel = new SectionChannel();
                break;
            default:
                throw new IllegalArgumentException("unsupported payload type: " + type);
        }

        channels.add(channel);
        return channel;
    }

    @Override
    public void closeChannel(TSDemux.Channel channel)
    {
        if (channel instanceof DemuxChannel)
        {
            if (channels.remove(channel))
                channel.setEnabled(false);
        }
    }

    ///////////////////////////////////////////////////////////////////////////

    /*
     * 实际读取和分析码流文件的过程，在单独的工作线程中执行。
     */
    class DemuxRoutine implements Runnable
    {
        private final RxChannel source;
        private final int initial_skips;
        private TSState prev_state;
        private TSState curr_state;
        private byte[] pktbuf; // 传输包缓冲区，支持188和204包长。
        private byte[] buffer; // 缓冲区，用于缓存从文件中读取的内容，16K。
        private int buffer_limit;
        private int buffer_position;
        private int frame_size;
        private long pcct;   // packet continuity counter
        private long prev_traffic;
        private long curr_traffic;

        DemuxRoutine(RxChannel input)
        {
            this(input, -1, 0);
        }

        DemuxRoutine(RxChannel input, int frame, int skips)
        {
            source = input;
            frame_size = frame;
            initial_skips = skips;
            prev_traffic = curr_traffic = 0;
        }

        @Override
        public void run()
        {
            post(new DemuxStatus(DefaultDemux.this, true));

            // 注意：解复用核心只使用，不更改复用流，因此不在这里关闭复用流。
            try
            {
                init();
                probe();

                while (keep_reading())
                {
                    // sync()、read()、dispatch()内部会根据当前状态决定
                    // 是否执行当前操作，或者转入下一个状态机。
                    // 把状态转换拆分隐藏在三个方法内，使得外部循环大大简化，
                    // 体现一种简单的美感。
                    sync();
                    read();
                    dispatch();

                    report_traffic();
                }
            } catch (EOFException eof)
            {
                System.err.println("stream terminated.");
            } catch (Exception other)
            {
                System.err.println("stream exception: " + other.getMessage());
            } finally
            {
                stop_reading = true;
                prev_state = curr_state;
                curr_state = TSState.INTERRUPTED;
                post(new TransportTraffic(DefaultDemux.this, curr_traffic));
                post(new TransportStatus(DefaultDemux.this, pcct, prev_state, curr_state));
            }

            post(new DemuxStatus(DefaultDemux.this, false));
            stop_reading = true; // double confirm
        }

        void post(TSDemuxEvent event)
        {
            listeners.forEach(listener ->
                              {
                                  try
                                  {
                                      listener.accept(event);
                                  } catch (RuntimeException ex)
                                  {
                                      System.err.println("listener exception: " + ex.getMessage());
                                  }
                              });
            channels.forEach(channel ->
                             {
                                 try
                                 {
                                     channel.handle(event);
                                 } catch (RuntimeException ex)
                                 {
                                     System.err.println("channel exception: " + ex.getMessage());
                                 }
                             });
        }

        void init()
        {
            pktbuf = new byte[MPEG2.TS_PACKET_SIZE + MPEG2.RS_CODE_SIZE];
            buffer = new byte[18800];
            buffer_limit = 0;
            buffer_position = 0;
            pcct = 0;
            stop_reading = false;
        }

        void probe() throws IOException
        {
            if (frame_size > 0)
            {
                // 外部提供了帧大小和初始同步点位置
                skip(initial_skips);
                prev_state = TSState.SYNC_LOST;
                curr_state = TSState.FINE;
                post(new TransportStatus(DefaultDemux.this, pcct, prev_state, curr_state));
                post(new PacketSizeReport(DefaultDemux.this, frame_size));
                return;
            }

            // [注意]
            // 这里假设复用流是单向流动的，因此当一个疑似同步点被否定时，不能折返回
            // 该同步点的后一字节，而必须从当前读位置开始寻找新的疑似同步点。
            // [说明]
            // 这里恢复到最初的同步锁定逻辑，与针对文件码流而进行的优化逻辑存在本质区别。
            // 这么做更通用，完全符合实时流模式下的判定结果，但应用到静态流模式时会浪费一些
            // 正确的数据，使得读到的包数略微减少。
            cache();

            // 获取帧大小（188|204）
            // 获取同步起始点
            // 尝试帧长为 188 字节
            frame_size = MPEG2.TS_PACKET_SIZE;
            if (seek_sync_point())
            {
                prev_state = TSState.SYNC_LOST;
                curr_state = TSState.FINE;
                post(new TransportStatus(DefaultDemux.this, pcct, prev_state, curr_state));
                post(new PacketSizeReport(DefaultDemux.this, frame_size));
                return;
            }

            // 尝试帧长为 204 字节
            frame_size = MPEG2.TS_PACKET_SIZE + MPEG2.RS_CODE_SIZE;
            if (seek_sync_point())
            {
                prev_state = TSState.SYNC_LOST;
                curr_state = TSState.FINE;
                post(new TransportStatus(DefaultDemux.this, pcct, prev_state, curr_state));
                post(new PacketSizeReport(DefaultDemux.this, frame_size));
                return;
            }

            // 帧长未知，或者码流无法同步。
            frame_size = -1;
            prev_state = TSState.SYNC_LOST;
            curr_state = TSState.SYNC_LOST;
            post(new TransportStatus(DefaultDemux.this, pcct, prev_state, curr_state));
            post(new DemuxStatus(DefaultDemux.this, false));
            stop_reading = true;

            System.err.println("fail to determine packet size, stop demux.");
        }

        boolean keep_reading()
        {
            return !stop_reading && curr_state != TSState.INTERRUPTED;
        }

        void sync() throws IOException
        {
            if (curr_state != TSState.SYNC_LOST)
                return;

            prev_state = curr_state;
            curr_state = seek_sync_point() ? TSState.FINE : TSState.SYNC_LOST;
            post(new TransportStatus(DefaultDemux.this, pcct, prev_state, curr_state));
        }

        void read() throws IOException
        {
            if (curr_state == TSState.SYNC_LOST ||
                curr_state == TSState.INTERRUPTED)
                return;

            // 从缓存中直接读取整个包数据
            int offset = 0;
            while (offset < frame_size)
            {
                // 从缓存中读取部分包数据，然后缓冲文件
                int usable = Math.min(frame_size - offset, // required
                                      buffer_limit - buffer_position);  // available
                System.arraycopy(buffer, buffer_position, pktbuf, offset, usable);
                offset += usable;
                buffer_position += usable;
                cache();
            }

            // 每读取一个包的数据，计数器就累加（即使后面因为同步丢失而实际未分配给通道）。
            // 这样既保证包计数值的自然性（而不是“差一的序数值”），也明确了“同步缺口”的位置。
            pcct = (pcct + 1) & 0xFFFFFFFFL; // 计数器实际只有32位长。
        }

        void dispatch()
        {
            if (curr_state == TSState.SYNC_LOST ||
                curr_state == TSState.INTERRUPTED)
                return;

            if (BigEndian.getUINT8(pktbuf, 0) == MPEG2.TS_SYNC_BYTE)
            {
                prev_state = curr_state;
                curr_state = TSState.FINE;
            } else
            {
                // 如果连续出现两次错误的同步字节值，则认为传输流同步丢失，需要重新同步。
                // 参见[TR-101290 Sec 5.2.1]。
                if (curr_state == TSState.SYNC_BYTE_ERROR)
                {
                    // 连续两次同步字节错误
                    // SYNC_BYTE_ERROR -> SYNC_LOST
                    prev_state = curr_state;
                    curr_state = TSState.SYNC_LOST;
                    post(new TransportStatus(DefaultDemux.this, read_pid(), pcct, prev_state, curr_state));
                    return;
                }

                // FINE -> SYNC_BYTE_ERROR
                prev_state = curr_state;
                curr_state = TSState.SYNC_BYTE_ERROR;
            }

            // FINE | SYNC_BYTE_ERROR
            if (prev_state != curr_state)
                post(new TransportStatus(DefaultDemux.this, read_pid(), pcct, prev_state, curr_state));

            if (curr_state != TSState.FINE)
                return;

            TransportPacket packet = new TransportPacket(DefaultDemux.this, pcct, Encoding.wrap(pktbuf, 0, MPEG2.TS_PACKET_SIZE));
            channels.forEach(channel ->
                             {
                                 try
                                 {
                                     channel.handle(packet);
                                 } catch (RuntimeException ex)
                                 {
                                     System.err.println("channel exception: " + ex.getMessage());
                                 }
                             });
        }

        void report_traffic()
        {
            // 初期以100KB为单位通报一次流量。
            if (curr_traffic - prev_traffic >= 102400)
            {
                post(new TransportTraffic(DefaultDemux.this, curr_traffic));
                prev_traffic = curr_traffic;
            }
        }

        boolean seek_sync_point() throws IOException
        {
            // 同步算法遵从 ISO.13818-1 的相关规定，连续识别5个同步字节视为获得同步。
            // 算法取舍：连续失败10次将结束同步。
            // 获得同步后，read()操作将从第五次同步字节出开始。

            int fails = 0;
            int count = 0;
            boolean found_first_sync_byte = false;

            while (fails < 10)
            {
                cache();

                if (buffer_remaining() == 0)
                {
                    fails++;
                    continue;
                }

                byte b = buffer[buffer_position];
                buffer_position += 1;

                if (b != 0x47)
                {
                    // 如果之前找到过疑似同步字节，那么这里应该是下一个确认点，但是没有出现期望的同步字节；
                    // 如果之前没有找到同步字节，那么继续逐字节寻找。
                    fails += found_first_sync_byte ? 1 : 0;
                    found_first_sync_byte = false;
                    count = 0;
                    continue;
                }

                // 首个疑似同步字节
                if (!found_first_sync_byte)
                {
                    found_first_sync_byte = true;
                    count = 1;
                    fails = 0;
                    skip(frame_size - 1);
                    continue;
                }

                // 连续出现的疑似同步字节
                count++;
                if (count == 5)
                {
                    // 调整缓存指针，确保下次read()操作从同步字节开始。
                    buffer_position--;
                    return true;
                } else
                {
                    skip(frame_size - 1);
                }
            }

            return false;
        }

        void skip(int n) throws IOException
        {
            while (n > 0)
            {
                int remaining = buffer_remaining();
                if (n < remaining)
                {
                    buffer_position += n;
                    return;
                }

                n -= remaining;
                cache();
            }
        }

        void cache() throws IOException
        {
            if (buffer_remaining() > 0)
                return;

            buffer_position = 0;
            buffer_limit = source.read(buffer, 0, buffer.length);
            if (buffer_limit == -1)
                throw new EOFException("no more data");

            curr_traffic += buffer_limit;
        }

        int buffer_remaining()
        {
            return buffer_limit - buffer_position;
        }

        int read_pid()
        {
            return BigEndian.getUINT16(pktbuf, 1) & 0x1FFF;
        }
    }

    abstract class DemuxChannel implements TSDemux.Channel
    {
        volatile int target_stream;
        volatile boolean channel_enabled;
        Consumer<TSDemuxPayload> payload_handler;

        DemuxChannel()
        {
            target_stream = ANY_PID;
            channel_enabled = false;
            payload_handler = null;
        }

        abstract void handle(TSDemuxEvent event);

        abstract void handle(TransportPacket packet);

        protected void forward_payload(TSDemuxPayload payload)
        {
            Consumer<TSDemuxPayload> handler = payload_handler;
            if (handler != null)
            {
                try
                {
                    handler.accept(payload);
                } catch (Throwable t)
                {
                    System.err.println("channel handler exception: {}" + t.getMessage());
                }
            }
        }

        @Override
        public TSDemux getHost()
        {
            return DefaultDemux.this;
        }

        @Override
        public int getStreamPID()
        {
            return target_stream;
        }

        @Override
        public void setStreamPID(int pid)
        {
            if (!channel_enabled)
                target_stream = pid;
        }

        @Override
        public boolean isEnabled()
        {
            return channel_enabled;
        }

        @Override
        public void setPayloadHandler(Consumer<TSDemuxPayload> handler)
        {
            if (!channel_enabled)
                payload_handler = handler;
        }
    }

    class RawChannel extends DemuxChannel
    {
        @Override
        void handle(TSDemuxEvent event)
        {
            // ignore event
        }

        @Override
        public void setEnabled(boolean enabled)
        {
            channel_enabled = enabled;
        }

        @Override
        void handle(TransportPacket packet)
        {
            if (!channel_enabled)
                return;

            Encoding encoding = packet.getEncoding();
            int pid = encoding.readUINT16(1) & MPEG2.PID_MASK;
            if (target_stream != ANY_PID && pid != target_stream)
                return;
            forward_payload(TSDemuxPayload.raw(this,
                                               packet.getPacketCounter(),
                                               packet.getEncoding()));
        }
    }

    class PESChannel extends DemuxChannel
    {
        int dupflag;
        byte[] pktbuf;
        byte[] buffer;
        int buffer_filled;
        int last_cct;
        long start_pct;  // PES包首字节所在包的计数器
        long finish_pct; // PES包末字节所在包的计数器

        PESChannel()
        {
            dupflag = ALLOW_DUPLICATE_PACKET;
            pktbuf = new byte[MPEG2.TS_PACKET_SIZE];
            buffer = new byte[MPEG2.MAX_PES_PACKET_LENGTH];
            buffer_filled = 0;
            last_cct = -1;
            start_pct = -1;
            finish_pct = -1;
        }

        @Override
        public void setEnabled(boolean enabled)
        {
            if (channel_enabled != enabled)
            {
                last_cct = -1;
                buffer_filled = 0;
                channel_enabled = enabled;
            }
        }

        @Override
        void handle(TSDemuxEvent event)
        {
            if (event instanceof TransportStatus)
            {
                TransportStatus status = (TransportStatus) event;
                if (status.getCurrentState() != TSState.FINE)
                {
                    last_cct = -1;
                    buffer_filled = 0;
                }
            }
        }

        @Override
        void handle(TransportPacket packet)
        {
            if (!channel_enabled)
                return;

            Encoding encoding = packet.getEncoding();
            int pid = encoding.readUINT16(1) & MPEG2.PID_MASK;
            if (pid != target_stream)
                return;

            // Transport Error Indicator
            int indicator = (encoding.readUINT8(1) >> 7) & 0b1;
            if (indicator == 1)
            {
                last_cct = -1;
                buffer_filled = 0;
                return; // 含有传输错误，负载数据无效。
            }

            // Adaptation Field Control
            int control = (encoding.readUINT8(3) >> 4) & 0b11;
            if (control == 0b00 || control == 0b10)
            {
                dupflag = NOT_ALLOW_DUPLICATE_PACKET;
                return; // 当前无有效负载（这里省略了检查CCT，不过不影响解码效果）。
            }

            // Continuity Counter
            int curr_cct = encoding.readUINT8(3) & 0b1111;
            int prev_cct = (curr_cct - 1) & 0b1111;
            if (last_cct == curr_cct && is_duplicate_packet(encoding))
            {
                dupflag = NOT_ALLOW_DUPLICATE_PACKET;
                return;
            }
            if (last_cct != -1 && last_cct != prev_cct)
            {
                last_cct = -1;
                buffer_filled = 0;
                return; // 连续计数器错误
            }

            last_cct = curr_cct;
            dupflag = ALLOW_DUPLICATE_PACKET;
            encoding.copyRange(0, MPEG2.TS_PACKET_SIZE, pktbuf);

            // 重组PES非常简单，不用考虑PES包编码，仅从PayloadUnitStartByte
            // 标志位就可以判断是否出现新的PES包，从而结束上一个PES包。
            indicator = (encoding.readUINT8(1) >> 6) & 0b1;
            if (indicator == 1)
            {
                if (buffer_filled != 0)
                {
                    // 结束上一个PES包
                    forward_payload(TSDemuxPayload.pes(this,
                                                       start_pct,
                                                       finish_pct,
                                                       pid,
                                                       Encoding.wrap(buffer, 0, buffer_filled)));
                }

                buffer_filled = 0;
                start_pct = packet.getPacketCounter();
            }

            control = (encoding.readUINT8(3) >> 4) & 0b11;
            int payload_start = (control == 0b01)
                                ? MPEG2.TS_PACKET_HEADER_SIZE
                                : MPEG2.TS_PACKET_HEADER_SIZE + 1 + encoding.readUINT8(4);
            encoding.copyRange(payload_start, MPEG2.TS_PACKET_SIZE, buffer, buffer_filled);
            buffer_filled += MPEG2.TS_PACKET_SIZE - payload_start;

            // 每次都更新结尾计数器，因为在不核对 PES_packet_length 的情况下
            // 无法提前知道 PES 数据是否完结。
            finish_pct = packet.getPacketCounter();
        }

        boolean is_duplicate_packet(Encoding encoding)
        {
            if (dupflag == NOT_ALLOW_DUPLICATE_PACKET)
                return false;

            return encoding.identicalTo(pktbuf) ||
                   encoding.identicalTo(pktbuf, DUPLICATE_PACKET_MASK);
        }
    }

    class SectionChannel extends DemuxChannel
    {
        int dupflag;
        byte[] pktbuf;
        byte[] buffer;
        int buffer_filled;
        int last_cct;
        int bytes_required;
        int payload_length;
        boolean header_complete;
        boolean payload_complete;
        long curr_pct;
        long start_pct;  // 段首字节所在包的计数器
        long finish_pct; // 段末字节所在包的计数器

        SectionChannel()
        {
            dupflag = ALLOW_DUPLICATE_PACKET;
            pktbuf = new byte[MPEG2.TS_PACKET_SIZE];
            buffer = new byte[MPEG2.MAX_PRIVATE_SECTION_LENGTH];
            buffer_filled = 0;
            last_cct = -1;
            header_complete = false;
            payload_complete = false;
            payload_length = -1;
            bytes_required = MPEG2.SECTION_HEADER_LENGTH;
            curr_pct = -1;
            start_pct = -1;
            finish_pct = -1;
        }

        @Override
        public void setEnabled(boolean enabled)
        {
            if (channel_enabled != enabled)
            {
                last_cct = -1;
                buffer_filled = 0;
                header_complete = false;
                payload_complete = false;
                bytes_required = MPEG2.SECTION_HEADER_LENGTH;
                channel_enabled = enabled;
            }
        }

        @Override
        void handle(TSDemuxEvent event)
        {
            if (event instanceof TransportStatus)
            {
                TransportStatus status = (TransportStatus) event;
                if (status.getCurrentState() != TSState.FINE)
                {
                    last_cct = -1;
                    buffer_filled = 0;
                    header_complete = false;
                    payload_complete = false;
                    bytes_required = MPEG2.SECTION_HEADER_LENGTH;
                }
            }
        }

        @Override
        void handle(TransportPacket packet)
        {
            if (!channel_enabled)
                return;

            Encoding encoding = packet.getEncoding();
            int pid = encoding.readUINT16(1) & MPEG2.PID_MASK;
            if (pid != target_stream)
                return;

            // Transport Error Indicator
            int indicator = (encoding.readUINT8(1) >> 7) & 0b1;
            if (indicator == 1)
            {
                last_cct = -1;
                buffer_filled = 0;
                header_complete = false;
                payload_complete = false;
                bytes_required = MPEG2.SECTION_HEADER_LENGTH;
                return; // 含有传输错误，负载数据无效。
            }

            // Adaptation Field Control
            int control = (encoding.readUINT8(3) >> 4) & 0b11;
            if (control == 0b00 || control == 0b10)
            {
                dupflag = NOT_ALLOW_DUPLICATE_PACKET;
                return; // 当前无有效负载。
            }

            // Continuity Counter
            int curr_cct = encoding.readUINT8(3) & 0b1111;
            int prev_cct = (curr_cct - 1) & 0b1111;
            if (last_cct == curr_cct && is_duplicate_packet(encoding))
            {
                dupflag = NOT_ALLOW_DUPLICATE_PACKET;
                return; // 重复包
            }
            if (last_cct != -1 && last_cct != prev_cct)
            {
                last_cct = -1;
                buffer_filled = 0;
                header_complete = false;
                payload_complete = false;
                bytes_required = MPEG2.SECTION_HEADER_LENGTH;
                return; // 连续计数器错误。
            }

            last_cct = curr_cct;
            curr_pct = packet.getPacketCounter();
            dupflag = ALLOW_DUPLICATE_PACKET;
            encoding.copyRange(0, MPEG2.TS_PACKET_SIZE, pktbuf);

            // Scrambling Control
            control = (encoding.readUINT8(3) >> 6) & 0b11;
            if (control != 0b00)
            {
                buffer_filled = 0;
                header_complete = false;
                payload_complete = false;
                bytes_required = MPEG2.SECTION_HEADER_LENGTH;
                return; // 暂不支持加扰的PSI(Section)流。
            }

            control = (encoding.readUINT8(3) >> 4) & 0b11;
            int payload_start = (control == 0b01)
                                ? MPEG2.TS_PACKET_HEADER_SIZE
                                : MPEG2.TS_PACKET_HEADER_SIZE + 1 + encoding.readUINT8(4);
            int pointer_field = -1;

            // Payload Unit Start Indicator
            indicator = (encoding.readUINT8(1) >> 6) & 0b1;
            if (indicator == 1)
            {
                // 跳过 pointer_field 字段。
                // pointer_field 之后不一定是新段的开始，
                // 有可能是上一个段的结尾。
                pointer_field = encoding.readUINT8(payload_start);
                payload_start += 1;

                if (payload_start + pointer_field >= MPEG2.TS_PACKET_SIZE)
                {
                    buffer_filled = 0;
                    header_complete = false;
                    payload_complete = false;
                    bytes_required = MPEG2.SECTION_HEADER_LENGTH;
                    return; // 从下一个正确的传输包开始解析。
                }
            }

            int offset = payload_start;
            while (offset < MPEG2.TS_PACKET_SIZE)
            {
                // 注：一个packet有可能携带多个section，所以尽可能多的进行解析。
                offset = extract_section(encoding, offset, payload_start, pointer_field);
                if (!payload_complete)
                    continue; // 尚未完成段的重组，继续循环。

                // 分段重组完毕。
                finish_pct = packet.getPacketCounter();
                forward_payload(TSDemuxPayload.section(this,
                                                       start_pct,
                                                       finish_pct,
                                                       pid,
                                                       Encoding.wrap(buffer, 0, buffer_filled)));

                // 重置上下文，准备重组下一分段。
                start_pct = finish_pct;
                buffer_filled = 0;
                header_complete = false;
                payload_complete = false;
                bytes_required = MPEG2.SECTION_HEADER_LENGTH;
            }
        }

        /**
         * 从包负载中提取分段数据。需要反复调用，并根据返回值及上下文判断是否提取到完整的分段。
         *
         * @param offset        本次操作的开始位置
         * @param payload_start 负载开始位置（不含 pointer_field）。
         * @param pointer_field -1，无 pointer_field；其他，pointer_field 值。
         * @return 本次操作的结束位置
         */
        int extract_section(Encoding encoding, int offset, int payload_start, int pointer_field)
        {
            // 写给未来某一时刻抽风的我：
            // 下面的代码应该是逻辑正确的，代码布局（风格）也做到了尽可能的简洁和一致，
            // 所以，请不要再“优化了”，已经为此浪费了很多时间和精力。切记！！！
            //
            // 2016-12-29: 我又手贱地优化了，为了适应新的解码模型。好像还修正几处逻辑问题（有待验证）。
            // 2017-01-20: 又找到了几个错误。

            if (buffer_filled == 0)
            {
                // 寻找段首字节。
                // 含有段首字节，就存在 pointer_field，反之亦然；下同。
                if (pointer_field == -1)
                {
                    // 在一个不含段首的负载中寻找段首字节
                    return MPEG2.TS_PACKET_SIZE;
                }

                // 第一个段首的位置（由 pointer_field 指定，外部保证这里不会越界）
                int section_start = payload_start + pointer_field;
                if (offset < section_start)
                {
                    // 当前位于第一个段首之前，而此时又需要查找段首字节，因此也视为一种错误情况。
                    // 但此时可以将指针移动到段首位置，而不是包末尾。
                    return section_start;
                }

                // offset位于第一个段首之后，可能是某一段首，也可能是段末的填充。
                if (encoding.readUINT8(offset) == MPEG2.TS_STUFFING_BYTE)
                {
                    // 填充字段，直至末尾。
                    return MPEG2.TS_PACKET_SIZE;
                }

                // 至此，至少可以读出一个有效的字节作为段首字节。
                start_pct = curr_pct;
                int freeBytes = Math.min(MPEG2.SECTION_HEADER_LENGTH, MPEG2.TS_PACKET_SIZE - offset);
                encoding.copyRange(offset, offset + freeBytes, buffer);
                buffer_filled = freeBytes;
                bytes_required -= freeBytes;

                if (bytes_required > 0)
                    return MPEG2.TS_PACKET_SIZE; // 剩余字节无法重组出段头

                header_complete = true;
                payload_length = BigEndian.getUINT16(buffer, 1) & 0x0FFF;
                bytes_required = payload_length;
                return offset + freeBytes; // 即使后续还有可用数据，这里也先结束重组。剩余部分由下面的追加逻辑完成。
            } else
            {
                // 开始追加数据
                int freeBytes = Math.min(bytes_required, MPEG2.TS_PACKET_SIZE - offset);
                encoding.copyRange(offset, offset + freeBytes, buffer, buffer_filled);
                buffer_filled += freeBytes;
                bytes_required -= freeBytes;

                if (bytes_required > 0)
                    return MPEG2.TS_PACKET_SIZE; // 剩余字节不够用。

                // 阶段性完成重组——段头或者段负载
                // 即使后续还有可用数据，这里也先结束重组。剩余部分继续由下一轮追加逻辑完成。
                if (header_complete)
                {
                    payload_complete = true;
                } else
                {
                    // 重组出段头部分。
                    header_complete = true;
                    payload_length = BigEndian.getUINT16(buffer, 1) & 0x0FFF;
                    bytes_required = payload_length;
                }
                return offset + freeBytes;
            }
        }

        boolean is_duplicate_packet(Encoding encoding)
        {
            if (dupflag == NOT_ALLOW_DUPLICATE_PACKET)
                return false;

            return encoding.identicalTo(pktbuf) ||
                   encoding.identicalTo(pktbuf, DUPLICATE_PACKET_MASK);
        }
    }
}
