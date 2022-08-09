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

public class TransportPacket extends TSDemuxEvent
{
    /**
     * 32位无符号计数器
     */
    private final long       pct;

    /**
     * 传输包数据（不含RS编码）
     */
    private final Encoding encoding;

    public TransportPacket(TSDemux source, long pct, Encoding encoding)
    {
        super(source);
        this.pct = pct;
        this.encoding = encoding;
    }

    public long getPacketCounter()
    {
        return pct;
    }

    public Encoding getEncoding()
    {
        return encoding;
    }
}
