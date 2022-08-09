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

package m2tk.test;

import m2tk.multiplex.TSFileProbe;
import org.junit.Test;

public class TSFileProbeTest
{
    @Test
    public void testFileProbe()
    {
        TSFileProbe probe = new TSFileProbe();
        probe.probe("C:\\Users\\yea\\Downloads\\TS\\125_0125_153856（广告）.ts");
        System.out.printf("File length: %,d bytes%n", probe.getLength());
        System.out.printf("Frame size: %d bytes%n", probe.getFrameSize());
        System.out.printf("Bitrate: %.2f Mbps%n", probe.getBitrate() / 1000000.0d);
    }
}
