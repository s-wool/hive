/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hive.spark.client.rpc;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.ByteBufferInputStream;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.google.common.base.Preconditions;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageCodec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Codec that serializes / deserializes objects using Kryo. Objects are encoded with a 4-byte
 * header with the length of the serialized data.
 */
class KryoMessageCodec extends ByteToMessageCodec<Object> {

  private static final Logger LOG = LoggerFactory.getLogger(KryoMessageCodec.class);

  // Kryo docs say 0-8 are taken. Strange things happen if you don't set an ID when registering
  // classes.
  private static final int REG_ID_BASE = 16;

  private final int maxMessageSize;
  private final List<Class<?>> messages;
  private final ThreadLocal<Kryo> kryos = new ThreadLocal<Kryo>() {
    @Override
    protected Kryo initialValue() {
      Kryo kryo = new Kryo();
      int count = 0;
      for (Class<?> klass : messages) {
        kryo.register(klass, REG_ID_BASE + count);
        count++;
      }
      return kryo;
    }
  };

  public KryoMessageCodec(int maxMessageSize, Class<?>... messages) {
    this.maxMessageSize = maxMessageSize;
    this.messages = Arrays.asList(messages);
  }

  @Override
  protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out)
      throws Exception {
    if (in.readableBytes() < 4) {
      return;
    }

    in.markReaderIndex();
    int msgSize = in.readInt();
    checkSize(msgSize);

    if (in.readableBytes() < msgSize) {
      // Incomplete message in buffer.
      in.resetReaderIndex();
      return;
    }

    try {
      ByteBuffer nioBuffer = in.nioBuffer(in.readerIndex(), msgSize);
      Input kryoIn = new Input(new ByteBufferInputStream(nioBuffer));

      Object msg = kryos.get().readClassAndObject(kryoIn);
      LOG.debug("Decoded message of type {} ({} bytes)",
          msg != null ? msg.getClass().getName() : msg, msgSize);
      out.add(msg);
    } finally {
      in.skipBytes(msgSize);
    }
  }

  @Override
  protected void encode(ChannelHandlerContext ctx, Object msg, ByteBuf buf)
      throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    Output kryoOut = new Output(bytes);
    kryos.get().writeClassAndObject(kryoOut, msg);
    kryoOut.flush();

    byte[] msgData = bytes.toByteArray();
    checkSize(msgData.length);

    buf.ensureWritable(msgData.length + 4);
    buf.writeInt(msgData.length);
    buf.writeBytes(msgData);
    LOG.debug("Encoded message of type {} ({} bytes)", msg.getClass().getName(), msgData.length);
  }

  private void checkSize(int msgSize) {
    Preconditions.checkArgument(msgSize > 0, "Message size must be positive.");
    Preconditions.checkArgument(maxMessageSize <= 0 || msgSize <= maxMessageSize,
        "Message exceeds maximum allowed size (%s bytes).", maxMessageSize);
  }

}
