/*
 * Copyright (c) 2022, FusionAuth, All Rights Reserved
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package io.fusionauth.http.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;

import io.fusionauth.http.io.NonBlockingByteBufferOutputStream;
import io.fusionauth.http.log.Logger;
import io.fusionauth.http.util.ThreadPool;

/**
 * A worker that handles a single request/response from a client.
 *
 * @author Brian Pontarelli
 */
public class HTTP11Processor implements HTTPProcessor {
  private final HTTPServerConfiguration configuration;

  private final Logger logger;

  private final Notifier notifier;

  private final ByteBuffer preambleBuffer;

  private final HTTPRequest request;

  private final HTTPRequestProcessor requestProcessor;

  private final HTTPResponse response;

  private final HTTPResponseProcessor responseProcessor;

  private final ThreadPool threadPool;

  private long lastUsed = System.currentTimeMillis();

  private volatile ProcessorState state;

  public HTTP11Processor(HTTPServerConfiguration configuration, HTTPListenerConfiguration listener, Notifier notifier,
                         ByteBuffer preambleBuffer, ThreadPool threadPool, String ipAddress) {
    this.configuration = configuration;
    this.logger = configuration.getLoggerFactory().getLogger(HTTP11Processor.class);
    this.notifier = notifier;
    this.preambleBuffer = preambleBuffer;
    this.threadPool = threadPool;
    this.state = ProcessorState.Read;

    this.request = new HTTPRequest(configuration.getContextPath(), configuration.getMultipartBufferSize(), listener.isTLS() ? "https" : "http", listener.getPort(), ipAddress);
    this.requestProcessor = new HTTPRequestProcessor(configuration, request);

    NonBlockingByteBufferOutputStream outputStream = new NonBlockingByteBufferOutputStream(notifier, configuration.getResponseBufferSize());
    this.response = new HTTPResponse(outputStream, request);
    this.responseProcessor = new HTTPResponseProcessor(configuration, request, response, outputStream);
  }

  @Override
  public ProcessorState close(boolean endOfStream) {
    logger.trace("(C)");

    // Set the state to Close and return it
    state = ProcessorState.Close;
    return state;
  }

  @Override
  public void failure(Throwable t) {
    logger.trace("(F)");

    // If we've written at least one byte back to the client, close the connection and bail. Otherwise, the failure was noted and the
    // Preamble will contain a 500 response. Therefore, we need to reset the processor, so it writes the preamble
    if (response.isCommitted()) {
      state = ProcessorState.Close;
    } else {
      // Maintain the `write` state but reset to Preamble to write the new headers
      state = ProcessorState.Write;
      responseProcessor.failure();
    }

    notifier.notifyNow();
  }

  /**
   * Non-TLS so always start by reading from the client.
   *
   * @return {@link SelectionKey#OP_READ}
   */
  @Override
  public int initialKeyOps() {
    logger.trace("(A)");

    return SelectionKey.OP_READ;
  }

  @Override
  public long lastUsed() {
    return lastUsed;
  }

  public void markUsed() {
    lastUsed = System.currentTimeMillis();
  }

  @Override
  public ProcessorState read(ByteBuffer buffer) throws IOException {
    markUsed();

    logger.trace("(R)");

    RequestState requestState = requestProcessor.state();
    if (requestState == RequestState.Preamble) {
      logger.trace("(RP)");

      requestState = requestProcessor.processPreambleBytes(buffer);

      // If the next state is not preamble, that means we are done processing that and ready to handle the request in a separate thread
      if (requestState != RequestState.Preamble && requestState != RequestState.Expect) {
        logger.trace("(RWo)");
        threadPool.submit(new HTTPWorker(configuration.getHandler(), configuration.getLoggerFactory(), this, request, response));
      }
    } else {
      logger.trace("(RB)");
      requestState = requestProcessor.processBodyBytes();
    }

    if (requestState == RequestState.Expect) {
      logger.trace("(RE)");

      var expectValidator = configuration.getExpectValidator();
      if (expectValidator != null) {
        expectValidator.validate(request, response);
      } else {
        response.setStatus(100);
      }

      responseProcessor.resetState(ResponseState.Expect);
      state = ProcessorState.Write;
    } else if (requestState == RequestState.Complete) {
      logger.trace("(RC)");
      state = ProcessorState.Write;
    }

    return state;
  }

  @Override
  public ByteBuffer readBuffer() {
    markUsed();

    RequestState state = requestProcessor.state();
    ByteBuffer buffer;
    if (state == RequestState.Preamble) {
      buffer = preambleBuffer;
    } else if (state == RequestState.Body) {
      buffer = requestProcessor.bodyBuffer();
    } else {
      buffer = null;
    }

    return buffer;
  }

  @Override
  public ProcessorState state() {
    return state;
  }

  @Override
  public ByteBuffer[] writeBuffers() {
    ResponseState responseState = responseProcessor.state();
    if (responseState == ResponseState.Expect || responseState == ResponseState.Preamble || responseState == ResponseState.Body) {
      return responseProcessor.currentBuffer();
    }

    return null;
  }

  @Override
  public ProcessorState wrote(long num) {
    markUsed();

    if (num > 0) {
      logger.trace("(W)");
      response.setCommitted(true);
    }

    // Determine the state transition based on the state of the response processor
    ResponseState responseState = responseProcessor.state();
    if (responseState == ResponseState.Continue) {
      logger.trace("(WCo)");

      // Flip back to reading and back to the preamble state, so we write the real response headers. Then start the worker thread and flip the ops
      requestProcessor.resetState(RequestState.Body);
      responseProcessor.resetState(ResponseState.Preamble);
      threadPool.submit(new HTTPWorker(configuration.getHandler(), configuration.getLoggerFactory(), this, request, response));
      state = ProcessorState.Read;
    } else if (responseState == ResponseState.KeepAlive) {
      logger.trace("(WKA)");
      state = ProcessorState.Reset;
    } else if (responseState == ResponseState.Close) {
      logger.trace("(WC)");
      state = ProcessorState.Close;
    }

    return state;
  }
}
