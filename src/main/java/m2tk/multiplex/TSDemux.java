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

import m2tk.io.RxChannel;

import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

public interface TSDemux
{
    static TSDemux newDefaultDemux(ExecutorService executor)
    {
        return new DefaultDemux(Objects.requireNonNull(executor));
    }

    /**
     * 关联复用传输流，开始解复用流程。
     *
     * @param source 复用传输流
     */
    void attach(RxChannel source);

    /**
     * 关联复用传输流，开始解复用流程。
     *
     * @param source 复用传输流
     * @param frameSize 传输流帧大小（188或204字节）
     * @param syncPoint 首个同步点位置
     */
    void attach(RxChannel source, int frameSize, int syncPoint);

    /**
     * 断开与资源的关联，停止解复用过程。
     */
    void detach();

    /**
     * 暂停当前线程，等待复用结束。
     */
    void join();

    /**
     * 关停解复用器，并关闭所有解复用通道。
     */
    void shutdown();

    /**
     * 注册解复用事件监听器
     *
     * @param listener 监听器
     * @return 是否注册成功
     */
    boolean registerEventListener(Consumer<TSDemuxEvent> listener);

    /**
     * 注销解复用事件监听器
     *
     * @param listener 监听器
     * @return 是否注销成功
     */
    boolean unregisterEventListener(Consumer<TSDemuxEvent> listener);

    /**
     * 清空解复用事件监听器，关闭所有通道，重置解复用器状态。
     */
    void reset();

    interface Channel
    {
        int ANY_PID = -1;

        TSDemux getHost();
        int getStreamPID();
        void setStreamPID(int pid);
        boolean isEnabled();
        void setEnabled(boolean enabled);
        void setPayloadHandler(Consumer<TSDemuxPayload> handler);
    }

    /**
     * 申请解复用通道。每个通道对应一个基本流（ES），允许用户改变目标流和负载处理器。
     *
     * @param type 负载类型
     * @return 通道对象
     */
    Channel requestChannel(TSDemuxPayload.Type type);

    /**
     * 关闭解复用通道。
     *
     * @param channel 解复用通道
     */
    void closeChannel(Channel channel);
}
