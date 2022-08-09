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

public class TSDemuxPayload
{
    public enum Type { RAW, PES, SECTION; }

    private final TSDemux.Channel channel;
    private final Type type;
    private final long start_pct;
    private final long finish_pct;
    private final int stream;
    private final Encoding encoding;

    public TSDemuxPayload(TSDemux.Channel channel, Type type, long pct1, long pct2, int pid, Encoding encoding)
    {
        this.channel = channel;
        this.type = type;
        this.start_pct = pct1;
        this.finish_pct = pct2;
        this.stream = pid;
        this.encoding = encoding;
    }

    public static TSDemuxPayload raw(TSDemux.Channel channel, long pct, Encoding encoding)
    {
        int pid = encoding.readUINT16(1) & MPEG2.PID_MASK;
        return new TSDemuxPayload(channel, Type.RAW, pct, pct, pid, encoding);
    }

    public static TSDemuxPayload pes(TSDemux.Channel channel, long start, long finish, int stream, Encoding encoding)
    {
        return new TSDemuxPayload(channel, Type.PES, start, finish, stream, encoding);
    }

    public static TSDemuxPayload section(TSDemux.Channel channel, long start, long finish, int stream, Encoding encoding)
    {
        return new TSDemuxPayload(channel, Type.SECTION, start, finish, stream, encoding);
    }

    public TSDemux.Channel getChannel()
    {
        return channel;
    }

    public Type getType()
    {
        return type;
    }

    public long getStartPacketCounter()
    {
        return start_pct;
    }

    public long getFinishPacketCounter()
    {
        return finish_pct;
    }

    public int getStreamPID()
    {
        return stream;
    }

    public Encoding getEncoding()
    {
        return encoding;
    }
}
