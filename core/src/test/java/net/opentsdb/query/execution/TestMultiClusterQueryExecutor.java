// This file is part of OpenTSDB.
// Copyright (C) 2016  The OpenTSDB Authors.
//
// This program is free software: you can redistribute it and/or modify it
// under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 2.1 of the License, or (at your
// option) any later version.  This program is distributed in the hope that it
// will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty
// of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser
// General Public License for more details.  You should have received a copy
// of the GNU Lesser General Public License along with this program.  If not,
// see <http://www.gnu.org/licenses/>.
package net.opentsdb.query.execution;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.Before;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import com.google.common.collect.Lists;
import com.google.common.reflect.TypeToken;
import com.stumbleupon.async.Deferred;
import com.stumbleupon.async.TimeoutException;

import io.netty.util.Timeout;
import io.netty.util.Timer;
import io.netty.util.TimerTask;
import io.opentracing.Span;
import net.opentsdb.data.DataMerger;
import net.opentsdb.exceptions.RemoteQueryExecutionException;
import net.opentsdb.query.context.QueryContext;
import net.opentsdb.query.context.RemoteContext;
import net.opentsdb.query.execution.MultiClusterQueryExecutor.QueryToClusterSplitter;
import net.opentsdb.query.pojo.TimeSeriesQuery;

public class TestMultiClusterQueryExecutor {
  private QueryContext context;
  private RemoteContext remote_context;
  private Timer timer;
  private TimeSeriesQuery query;
  private ClusterConfig cluster_a;
  private ClusterConfig cluster_b;
  private QueryExecutor<Long> executor_a;
  private QueryExecutor<Long> executor_b;
  private MockDownstream downstream_a;
  private MockDownstream downstream_b;
  private DataMerger<Long> merger;
  private Timeout timeout;
  private Span span;
  
  @SuppressWarnings("unchecked")
  @Before
  public void before() throws Exception {
    context = mock(QueryContext.class);
    remote_context = mock(RemoteContext.class);
    timer = mock(Timer.class);
    query = mock(TimeSeriesQuery.class);
    cluster_a = mock(ClusterConfig.class);
    cluster_b = mock(ClusterConfig.class);
    executor_a = mock(QueryExecutor.class);
    executor_b = mock(QueryExecutor.class);
    downstream_a = new MockDownstream(query);
    downstream_b = new MockDownstream(query);
    merger = mock(DataMerger.class);
    timeout = mock(Timeout.class);
    span = mock(Span.class);
    
    when(context.getRemoteContext()).thenReturn(remote_context);
    when(context.getTimer()).thenReturn(timer);
    when(timer.newTimeout(any(TimerTask.class), anyLong(), 
        eq(TimeUnit.MILLISECONDS))).thenReturn(timeout);
    when(remote_context.dataMerger(any(TypeToken.class)))
      .thenAnswer(new Answer<DataMerger<?>>() {
      @Override
      public DataMerger<?> answer(InvocationOnMock invocation)
          throws Throwable {
        return merger;
      }
    });
    when(remote_context.clusters()).thenReturn(
        Lists.newArrayList(cluster_a, cluster_b));
    when(merger.type()).thenAnswer(new Answer<TypeToken<?>>() {
      @Override
      public TypeToken<?> answer(InvocationOnMock invocation) throws Throwable {
        return TypeToken.of(Long.class);
      }
    });
    when(cluster_a.remoteExecutor()).thenAnswer(new Answer<QueryExecutor<?>>() {
      @Override
      public QueryExecutor<?> answer(InvocationOnMock invocation)
          throws Throwable {
        return executor_a;
      }
    });
    when(cluster_b.remoteExecutor()).thenAnswer(new Answer<QueryExecutor<?>>() {
      @Override
      public QueryExecutor<?> answer(InvocationOnMock invocation)
          throws Throwable {
        return executor_b;
      }
    });
    when(executor_a.executeQuery(eq(query), any(Span.class))).thenReturn(downstream_a);
    when(executor_b.executeQuery(eq(query), any(Span.class))).thenReturn(downstream_b);
    when(executor_a.close()).thenReturn(Deferred.fromResult(null));
    when(executor_b.close()).thenReturn(Deferred.fromResult(null));
    when(merger.merge((List<Long>) any(List.class))).thenAnswer(new Answer<Long>() {
      @Override
      public Long answer(InvocationOnMock invocation) throws Throwable {
        return 42L;
      }
    });
  }
  
  @Test
  public void ctor() throws Exception {
    MultiClusterQueryExecutor<Long> executor = 
        new MultiClusterQueryExecutor<Long>(context, Long.class);
    assertSame(merger, executor.dataMerger());
    assertTrue(executor.outstandingExecutors().isEmpty());
    
    executor = new MultiClusterQueryExecutor<Long>(context, Long.class, 60000);
    assertSame(merger, executor.dataMerger());
    assertTrue(executor.outstandingExecutors().isEmpty());
    
    try {
      new MultiClusterQueryExecutor<Long>(null, Long.class);
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) { }
    
    try {
      new MultiClusterQueryExecutor<Long>(context, null);
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) { }
    
    when(merger.type()).thenAnswer(new Answer<TypeToken<?>>() {
      @Override
      public TypeToken<?> answer(InvocationOnMock invocation) throws Throwable {
        return TypeToken.of(String.class);
      }
    });
    try {
      new MultiClusterQueryExecutor<Long>(context, Long.class);
      fail("Expected IllegalStateException");
    } catch (IllegalStateException e) { }
    
    when(remote_context.dataMerger(any(TypeToken.class))).thenReturn(null);
    try {
      new MultiClusterQueryExecutor<Long>(context, Long.class);
      fail("Expected IllegalStateException");
    } catch (IllegalStateException e) { }
  }

  @SuppressWarnings("rawtypes")
  @Test
  public void execute() throws Exception {
    final MultiClusterQueryExecutor<Long> executor = 
        new MultiClusterQueryExecutor<Long>(context, Long.class);
    
    final QueryExecution<Long> exec = executor.executeQuery(query, span);
    try {
      exec.deferred().join(1);
      fail("Expected TimeoutException");
    } catch (TimeoutException e) { }
    
    verify(remote_context, times(1)).clusters();
    verify(executor_a, times(1)).executeQuery(query, null);
    verify(executor_b, times(1)).executeQuery(query, null);
    assertTrue(executor.outstandingExecutors().contains(exec));
    assertFalse(exec.completed());
    assertFalse(downstream_a.cancelled);
    assertFalse(downstream_a.completed());
    assertFalse(downstream_b.cancelled);
    assertFalse(downstream_b.completed());
    assertTrue(exec instanceof QueryToClusterSplitter);
    assertSame(downstream_a, ((QueryToClusterSplitter) exec).executions[0]);
    assertSame(downstream_b, ((QueryToClusterSplitter) exec).executions[1]);
    
    downstream_a.callback(1L);
    downstream_b.callback(2L);
    
    assertEquals(42L, (long) exec.deferred().join(1));
    assertFalse(executor.outstandingExecutors().contains(exec));
    assertTrue(exec.completed());
    assertFalse(downstream_a.cancelled);
    assertTrue(downstream_a.completed());
    assertFalse(downstream_b.cancelled);
    assertTrue(downstream_b.completed());
  }
  
  @SuppressWarnings("rawtypes")
  @Test
  public void executeWithTimeout() throws Exception {
    final MultiClusterQueryExecutor<Long> executor = 
        new MultiClusterQueryExecutor<Long>(context, Long.class, 60000);
    
    final QueryExecution<Long> exec = executor.executeQuery(query, span);
    try {
      exec.deferred().join(1);
      fail("Expected TimeoutException");
    } catch (TimeoutException e) { }
    
    verify(remote_context, times(1)).clusters();
    verify(executor_a, times(1)).executeQuery(query, null);
    verify(executor_b, times(1)).executeQuery(query, null);
    assertTrue(executor.outstandingExecutors().contains(exec));
    assertFalse(exec.completed());
    assertFalse(downstream_a.cancelled);
    assertFalse(downstream_a.completed());
    assertFalse(downstream_b.cancelled);
    assertFalse(downstream_b.completed());
    assertTrue(exec instanceof QueryToClusterSplitter);
    assertSame(downstream_a, ((QueryToClusterSplitter) exec).executions[0]);
    assertSame(downstream_b, ((QueryToClusterSplitter) exec).executions[1]);
    
    downstream_a.callback(1L);
    
    // should have started the timer task
    try {
      exec.deferred().join(1);
      fail("Expected TimeoutException");
    } catch (TimeoutException e) { }
    verify(timer, times(1)).newTimeout((TimerTask) exec, 60000, 
        TimeUnit.MILLISECONDS);
    assertTrue(executor.outstandingExecutors().contains(exec));
    assertFalse(exec.completed());
    assertFalse(downstream_a.cancelled);
    assertTrue(downstream_a.completed());
    assertFalse(downstream_b.cancelled);
    assertFalse(downstream_b.completed());
    
    // pretend it timed out
    ((TimerTask) exec).run(null);
    assertEquals(42L, (long) exec.deferred().join()); // still mocked
    assertFalse(executor.outstandingExecutors().contains(exec));
    assertTrue(exec.completed());
    assertTrue(downstream_a.cancelled);
    assertTrue(downstream_a.completed());
    assertTrue(downstream_b.cancelled);
    assertFalse(downstream_b.completed());
    verify(timeout, never()).cancel();
  }
  
  @SuppressWarnings("rawtypes")
  @Test
  public void executeWithTimeoutAllSuccessful() throws Exception {
    final MultiClusterQueryExecutor<Long> executor = 
        new MultiClusterQueryExecutor<Long>(context, Long.class, 60000);
    
    final QueryExecution<Long> exec = executor.executeQuery(query, span);
    try {
      exec.deferred().join(1);
      fail("Expected TimeoutException");
    } catch (TimeoutException e) { }
    
    verify(remote_context, times(1)).clusters();
    verify(executor_a, times(1)).executeQuery(query, null);
    verify(executor_b, times(1)).executeQuery(query, null);
    assertTrue(executor.outstandingExecutors().contains(exec));
    assertFalse(exec.completed());
    assertFalse(downstream_a.cancelled);
    assertFalse(downstream_a.completed());
    assertFalse(downstream_b.cancelled);
    assertFalse(downstream_b.completed());
    assertTrue(exec instanceof QueryToClusterSplitter);
    assertSame(downstream_a, ((QueryToClusterSplitter) exec).executions[0]);
    assertSame(downstream_b, ((QueryToClusterSplitter) exec).executions[1]);
    
    downstream_a.callback(1L);
    
    // should have started the timer task
    try {
      exec.deferred().join(1);
      fail("Expected TimeoutException");
    } catch (TimeoutException e) { }
    verify(timer, times(1)).newTimeout((TimerTask) exec, 60000, 
        TimeUnit.MILLISECONDS);
    assertTrue(executor.outstandingExecutors().contains(exec));
    assertFalse(exec.completed());
    assertFalse(downstream_a.cancelled);
    assertTrue(downstream_a.completed());
    assertFalse(downstream_b.cancelled);
    assertFalse(downstream_b.completed());
    
    // second data makes it in
    downstream_b.callback(2L);
    assertEquals(42L, (long) exec.deferred().join()); // still mocked
    assertFalse(executor.outstandingExecutors().contains(exec));
    assertTrue(exec.completed());
    assertFalse(downstream_a.cancelled);
    assertTrue(downstream_a.completed());
    assertFalse(downstream_b.cancelled);
    assertTrue(downstream_b.completed());
    verify(timeout, times(1)).cancel();
    
    // race!
    ((TimerTask) exec).run(null);
    assertEquals(42L, (long) exec.deferred().join()); // still mocked
  }
  
  @Test
  public void executeRemoteContextThrowsException() throws Exception {
    final MultiClusterQueryExecutor<Long> executor = 
        new MultiClusterQueryExecutor<Long>(context, Long.class);
    // exception thrown
    when(remote_context.clusters())
      .thenThrow(new IllegalArgumentException("Boo!"));
    
    final QueryExecution<Long> exec = executor.executeQuery(query, span);
    try {
      exec.deferred().join();
      fail("Expected RemoteQueryExecutionException");
    } catch (RemoteQueryExecutionException e) { }
    verify(remote_context, times(1)).clusters();
    verify(executor_a, never()).executeQuery(query, span);
    verify(executor_b, never()).executeQuery(query, span);
    assertFalse(executor.outstandingExecutors().contains(exec));
    assertTrue(exec.completed());
  }
  
  @Test
  public void executeClustersNull() throws Exception {
    final MultiClusterQueryExecutor<Long> executor = 
        new MultiClusterQueryExecutor<Long>(context, Long.class);
    when(remote_context.clusters()).thenReturn(null);
    
    final QueryExecution<Long> exec = executor.executeQuery(query, span);
    try {
      exec.deferred().join();
      fail("Expected RemoteQueryExecutionException");
    } catch (RemoteQueryExecutionException e) { }
    verify(remote_context, times(1)).clusters();
    verify(executor_a, never()).executeQuery(query, span);
    verify(executor_b, never()).executeQuery(query, span);
    assertFalse(executor.outstandingExecutors().contains(exec));
    assertTrue(exec.completed());
  }
  
  @Test
  public void executeClustersEmpty() throws Exception {
    final MultiClusterQueryExecutor<Long> executor = 
        new MultiClusterQueryExecutor<Long>(context, Long.class);
    when(remote_context.clusters()).thenReturn(
        Collections.<ClusterConfig>emptyList());
    
    final QueryExecution<Long> exec = executor.executeQuery(query, span);
    try {
      exec.deferred().join();
      fail("Expected RemoteQueryExecutionException");
    } catch (RemoteQueryExecutionException e) { }
    verify(remote_context, times(1)).clusters();
    verify(executor_a, never()).executeQuery(query, span);
    verify(executor_b, never()).executeQuery(query, span);
    assertFalse(executor.outstandingExecutors().contains(exec));
    assertTrue(exec.completed());
  }
  
  @Test
  public void executeDownstreamThrew() throws Exception {
    final MultiClusterQueryExecutor<Long> executor = 
        new MultiClusterQueryExecutor<Long>(context, Long.class);
    when(executor_b.executeQuery(eq(query), any(Span.class)))
      .thenThrow(new IllegalArgumentException("Boo!"));
    
    final QueryExecution<Long> exec = executor.executeQuery(query, span);
    try {
      exec.deferred().join();
      fail("Expected RemoteQueryExecutionException");
    } catch (RemoteQueryExecutionException e) { }
    
    verify(remote_context, times(1)).clusters();
    verify(executor_a, times(1)).executeQuery(query, null);
    verify(executor_b, times(1)).executeQuery(query, null);
    assertFalse(executor.outstandingExecutors().contains(exec));
    assertTrue(exec.completed());
    assertTrue(downstream_a.cancelled); // made it through so we cancel it.
    assertFalse(downstream_a.completed());
    assertFalse(downstream_b.cancelled);
    assertFalse(downstream_b.completed());
    verify(merger, never()).merge(any(List.class));
  }
  
  @Test
  public void executeOneBadOneGood() throws Exception {
    final MultiClusterQueryExecutor<Long> executor = 
        new MultiClusterQueryExecutor<Long>(context, Long.class);
    
    final QueryExecution<Long> exec = executor.executeQuery(query, span);
    try {
      exec.deferred().join(1);
      fail("Expected TimeoutException");
    } catch (TimeoutException e) { }
    
    downstream_a.callback(new RemoteQueryExecutionException("Boo!"));
    downstream_b.callback(1L);
    
    assertEquals(42L, (long) exec.deferred().join());
    
    verify(remote_context, times(1)).clusters();
    verify(executor_a, times(1)).executeQuery(query, null);
    verify(executor_b, times(1)).executeQuery(query, null);
    assertFalse(executor.outstandingExecutors().contains(exec));
    assertTrue(exec.completed());
    assertFalse(downstream_a.cancelled); // made it through so we cancel it.
    assertTrue(downstream_a.completed());
    assertFalse(downstream_b.cancelled);
    assertTrue(downstream_b.completed());
    verify(merger, times(1)).merge(any(List.class));
  }
  
  @Test
  public void executeBothBad() throws Exception {
    final MultiClusterQueryExecutor<Long> executor = 
        new MultiClusterQueryExecutor<Long>(context, Long.class);
    
    final QueryExecution<Long> exec = executor.executeQuery(query, span);
    try {
      exec.deferred().join(1);
      fail("Expected TimeoutException");
    } catch (TimeoutException e) { }
    
    // higher status wins
    final RemoteQueryExecutionException e1 = 
        new RemoteQueryExecutionException("Boo!", 0, 500);
    final RemoteQueryExecutionException e2 = 
        new RemoteQueryExecutionException("Boo!", 0 , 408);
    
    downstream_a.callback(e1);
    downstream_b.callback(e2);
    
    try {
      exec.deferred().join();
      fail("Expected RemoteQueryExecutionException");
    } catch (RemoteQueryExecutionException e) { 
      assertEquals(2, e.getExceptions().size());
      assertSame(e1, e.getExceptions().get(0));
      assertSame(e2, e.getExceptions().get(1));
      assertEquals(500, e.getStatusCode());
    }
    
    verify(remote_context, times(1)).clusters();
    verify(executor_a, times(1)).executeQuery(query, null);
    verify(executor_b, times(1)).executeQuery(query, null);
    assertFalse(executor.outstandingExecutors().contains(exec));
    assertTrue(exec.completed());
    assertFalse(downstream_a.cancelled); // made it through so we cancel it.
    assertTrue(downstream_a.completed());
    assertFalse(downstream_b.cancelled);
    assertTrue(downstream_b.completed());
    verify(merger, never()).merge(any(List.class));
  }
  
  @Test
  public void executeCancel() throws Exception {
    final MultiClusterQueryExecutor<Long> executor = 
        new MultiClusterQueryExecutor<Long>(context, Long.class);
    
    final QueryExecution<Long> exec = executor.executeQuery(query, span);
    try {
      exec.deferred().join(1);
      fail("Expected TimeoutException");
    } catch (TimeoutException e) { }
    
    exec.cancel();
    
    try {
      exec.deferred().join();
      fail("Expected RemoteQueryExecutionException");
    } catch (RemoteQueryExecutionException e) { }
    
    verify(remote_context, times(1)).clusters();
    verify(executor_a, times(1)).executeQuery(query, null);
    verify(executor_b, times(1)).executeQuery(query, null);
    assertFalse(executor.outstandingExecutors().contains(exec));
    assertTrue(exec.completed());
    assertTrue(downstream_a.cancelled); // made it through so we cancel it.
    assertFalse(downstream_a.completed());
    assertTrue(downstream_b.cancelled);
    assertFalse(downstream_b.completed());
    verify(merger, never()).merge(any(List.class));
  }
  
  @Test
  public void close() throws Exception {
    final MultiClusterQueryExecutor<Long> executor = 
        new MultiClusterQueryExecutor<Long>(context, Long.class);
    
    final QueryExecution<Long> exec = executor.executeQuery(query, span);
    try {
      exec.deferred().join(1);
      fail("Expected TimeoutException");
    } catch (TimeoutException e) { }
    
    assertNull(executor.close().join());
    verify(remote_context, times(1)).clusters();
    verify(executor_a, times(1)).executeQuery(query, null);
    verify(executor_b, times(1)).executeQuery(query, null);
    assertFalse(executor.outstandingExecutors().contains(exec));
    assertTrue(exec.completed());
    assertTrue(downstream_a.cancelled); // made it through so we cancel it.
    assertFalse(downstream_a.completed());
    assertTrue(downstream_b.cancelled);
    assertFalse(downstream_b.completed());
    verify(merger, never()).merge(any(List.class));
  }

  /** Simple implementation to peek into the cancel call. */
  class MockDownstream extends QueryExecution<Long> {
    boolean cancelled;
    
    public MockDownstream(TimeSeriesQuery query) {
      super(query);
    }

    @Override
    public void cancel() {
      cancelled = true;
    }
    
  }
}