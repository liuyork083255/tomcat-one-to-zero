/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.coyote;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.apache.tomcat.util.net.AbstractEndpoint.Handler.SocketState;
import org.apache.tomcat.util.net.SSLSupport;
import org.apache.tomcat.util.net.SocketEvent;
import org.apache.tomcat.util.net.SocketWrapperBase;

/**
 * Common interface for processors of all protocols.
 *
 * 职责:协议解析，不同的协议对应不同的 processor
 * otz:
 *  属于 connector 部分，请求接入后首先到 Endpoint 组件，然后就会调用 Processor
 *  Processor 主要根据自己具体实现的协议来解析请求，
 *  比如是http协议，就会解析 请求行、请求头等，解析完成功能后负责构造 request 和 response 对象，
 *  然后通过 Adapter 将请求往后传递，参考{@link org.apache.coyote.http11.Http11Processor#service}
 *  所以 processor 必然会持有 adapter 对象 {@link AbstractProcessor#adapter}
 *
 *
 *  Processor 如何映射到 container？ -> {@link org.apache.catalina.mapper.Mapper}
 *
 * tomcat 按照协议提供了三个实现类
 *  {@link org.apache.coyote.http11.Http11Processor} HTTP/1.1
 *  {@link org.apache.coyote.ajp.AjpProcessor}  AJP
 *  {@link org.apache.coyote.http2.StreamProcessor} HTTP/2.0
 *
 */
public interface Processor {

    /**
     * Process a connection. This is called whenever an event occurs (e.g. more
     * data arrives) that allows processing to continue for a connection that is
     * not currently being processed.
     *
     * @param socketWrapper The connection to process
     * @param status The status of the connection that triggered this additional
     *               processing
     *
     * @return The state the caller should put the socket in when this method
     *         returns
     *
     * @throws IOException If an I/O error occurs during the processing of the
     *         request
     */
    SocketState process(SocketWrapperBase<?> socketWrapper, SocketEvent status) throws IOException;

    /**
     * Generate an upgrade token.
     *
     * @return An upgrade token encapsulating the information required to
     *         process the upgrade request
     *
     * @throws IllegalStateException if this is called on a Processor that does
     *         not support upgrading
     */
    UpgradeToken getUpgradeToken();

    /**
     * @return {@code true} if the Processor is currently processing an upgrade
     *         request, otherwise {@code false}
     */
    boolean isUpgrade();
    boolean isAsync();

    /**
     * Check this processor to see if the async timeout has expired and process
     * a timeout if that is that case.
     *
     * @param now The time (as returned by {@link System#currentTimeMillis()} to
     *            use as the current time to determine whether the async timeout
     *            has expired. If negative, the timeout will always be treated
     *            as if it has expired.
     */
    void timeoutAsync(long now);

    /**
     * @return The request associated with this processor.
     */
    Request getRequest();

    /**
     * Recycle the processor, ready for the next request which may be on the
     * same connection or a different connection.
     */
    void recycle();

    /**
     * Set the SSL information for this HTTP connection.
     *
     * @param sslSupport The SSL support object to use for this connection
     */
    void setSslSupport(SSLSupport sslSupport);

    /**
     * Allows retrieving additional input during the upgrade process.
     *
     * @return leftover bytes
     *
     * @throws IllegalStateException if this is called on a Processor that does
     *         not support upgrading
     */
    ByteBuffer getLeftoverInput();

    /**
     * Informs the processor that the underlying I/O layer has stopped accepting
     * new connections. This is primarily intended to enable processors that
     * use multiplexed connections to prevent further 'streams' being added to
     * an existing multiplexed connection.
     */
    void pause();

    /**
     * Check to see if the async generation (each cycle of async increments the
     * generation of the AsyncStateMachine) is the same as the generation when
     * the most recent async timeout was triggered. This is intended to be used
     * to avoid unnecessary processing.
     *
     * @return {@code true} If the async generation has not changed since the
     *         async timeout was triggered
     */
    boolean checkAsyncTimeoutGeneration();
}
