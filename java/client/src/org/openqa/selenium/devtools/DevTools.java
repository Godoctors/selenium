// Licensed to the Software Freedom Conservancy (SFC) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The SFC licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.openqa.selenium.devtools;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.io.Closeable;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

public class DevTools implements Closeable {

  private final Duration timeout = Duration.ofSeconds(10);
  private final Connection connection;
  private Target.SessionId cdpSession = null;

  public DevTools(Connection connection) {
    this.connection = connection;
  }

  @Override
  public void close() {
    connection.close();
  }

  public <X> X send(Command<X> command) {
    Objects.requireNonNull(command, "Command to send must be set.");
    return connection.sendAndWait(cdpSession, command, timeout);
  }

  public <X> void addListener(Event<X> event, Consumer<X> handler) {
    Objects.requireNonNull(event, "Event to listen for must be set.");
    Objects.requireNonNull(handler, "Handler to call must be set.");

    connection.addListener(event, handler);
  }

  public void createSession() {
    // Figure out the targets.
    Set<Target.TargetInfo> infos = connection.sendAndWait(cdpSession, Target.getTargets(), timeout);

    // Grab the first "page" type, and glom on to that.
    // TODO: Find out which one might be the current one
    Target.TargetId targetId = infos.stream()
        .filter(info -> "page".equals(info.getType()))
        .map(Target.TargetInfo::getTargetId)
        .findAny()
        .orElseThrow(() -> new DevToolsException("Unable to find target id of a page"));

    // Start the session.
    cdpSession = connection.sendAndWait(cdpSession, Target.attachToTarget(targetId), timeout);

    try {
      // We can do all of these in parallel, and we don't care about the result.
      CompletableFuture.allOf(
          // Set auto-attach to true and run for the hills.
          connection.send(cdpSession, Target.setAutoAttach(true)),
          // Clear the existing logs
          connection.send(cdpSession, Log.clear()))
          .get(timeout.toMillis(), MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Thread has been interrupted", e);
    } catch (ExecutionException e) {
      Throwable cause = e;
      if (e.getCause() != null) {
        cause = e.getCause();
      }
      throw new DevToolsException(cause);
    } catch (TimeoutException e) {
      throw new org.openqa.selenium.TimeoutException(e);
    }
  }
}
