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

public class TransportStatus extends TSDemuxEvent
{
    private final int        pid;
    private final long       position;
    private final TSState    previous;
    private final TSState    current;

    public TransportStatus(TSDemux source, long position, TSState previous, TSState current)
    {
        this(source, -1, position, previous, current);
    }

    public TransportStatus(TSDemux source, int pid, long position, TSState previous, TSState current)
    {
        super(source);
        this.pid = pid;
        this.position = position;
        this.previous = previous;
        this.current = current;
    }

    public int getPid()
    {
        return pid;
    }

    public long getPosition()
    {
        return position;
    }

    public TSState getPreviousState()
    {
        return previous;
    }

    public TSState getCurrentState()
    {
        return current;
    }
}
