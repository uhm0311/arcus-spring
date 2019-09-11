/*
 * arcus-spring - Arcus as a caching provider for the Spring Cache Abstraction
 * Copyright 2011-2014 NAVER Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.navercorp.arcus.spring.cache;

import com.navercorp.arcus.spring.concurrent.DefaultKeyLockProvider;
import com.navercorp.arcus.spring.concurrent.KeyLockProvider;
import net.spy.memcached.ArcusClientPool;
import net.spy.memcached.internal.OperationFuture;
import net.spy.memcached.transcoders.Transcoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.cache.Cache;
import org.springframework.cache.support.SimpleValueWrapper;
import org.springframework.util.Assert;
import org.springframework.util.DigestUtils;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 스프링 Cache의 Arcus 구현체.
 * <p>
 * Arcus의 key 구조는 prefix:subkey 입니다. prefix는 사용자가 그룹으로 생성하고자 하는 subkey들의 집합이며
 * ArcusCache에서는 서비스 또는 빌드 단계 등의 구분을 위해 serviceId + name으로 정의합니다. serviceCode
 * 속성과 name는 반드시 설정되어야 합니다.
 * <p>
 * <pre class="code">
 * <p>
 * <bean id="operationTranscoderA" class="net.spy.memcached.transcoders.SerializingTranscoder">
 * <property name="charset" value="UTF-8" />
 * <property name="compressionThreshold" value="400" />
 * </bean>
 * <p>
 * <bean id="operationTranscoderB" class="net.spy.memcached.transcoders.SerializingTranscoder">
 * <property name="charset" value="UTF-8" />
 * <property name="compressionThreshold" value="1024" />
 * </bean>
 * <p>
 * <bean id="arcusCacheManager" class="org.springframework.cache.support.SimpleCacheManager">
 * <property name="caches">
 * <list>
 * <bean p:name="member" p:timeoutMilliSeconds="500" parent="defaultArcusCache" p:operationTranscoder-ref="operationTranscoderA" />
 * <bean p:name="memberList" p:expireSeconds="3000"	parent="defaultArcusCache" p:operationTranscoder-ref="operationTranscoderB" />
 * </list>
 * </property>
 * </bean>
 * <p>
 * <bean id="defaultArcusCache" class="com.navercorp.arcus.spring.cache.ArcusCache"
 * p:arcusClient-ref="arcusClient" p:timeoutMilliSeconds="500"
 * p:expireSeconds="3000" abstract="true" serviceId="beta-" />
 * <p>
 * </pre>
 * <p>
 * 이렇게 설정했을때, 캐시의 키 값으로 생성되는 값은 <span>beta-member:메서드 매개변수로 만든 문자열</span>이 됩니다.
 */
@SuppressWarnings("DeprecatedIsStillUsed")
public class ArcusCache implements Cache, InitializingBean {

  private Logger logger = LoggerFactory.getLogger(this.getClass());

  private String name;
  private String prefix;
  private String serviceId;
  private int expireSeconds;
  private long timeoutMilliSeconds = 300L;
  private ArcusClientPool arcusClient;
  @Deprecated
  private boolean wantToGetException;
  private Transcoder<Object> operationTranscoder;
  private KeyLockProvider keyLockProvider = new DefaultKeyLockProvider();

  @Override
  public String getName() {
    return this.name;
  }

  @Override
  public Object getNativeCache() {
    return this.arcusClient;
  }

  @Override
  public ValueWrapper get(Object key) {
    Object value = null;
    String cacheKey = null;
    try {
      cacheKey = createArcusKey(key);
      logger.debug("getting value by key: {}", cacheKey);

      Future<Object> future;

      // operation transcoder can't be null.
      if (operationTranscoder != null) {
        future = arcusClient.asyncGet(cacheKey, operationTranscoder);
      } else {
        future = arcusClient.asyncGet(cacheKey);
      }

      value = future.get(timeoutMilliSeconds, TimeUnit.MILLISECONDS);
    } catch (Exception e) {
      logger.debug(e.getMessage());
      if (wantToGetException) {
        throw new RuntimeException(e);
      }
    }
    return (value != null ? new SimpleValueWrapper(value) : null);
  }

  @SuppressWarnings("unchecked")
  @Override
  public <T> T get(Object key, Class<T> type) {
    try {
      Object value = getValue(createArcusKey(key));
      if (value != null && type != null && !type.isInstance(value)) {
        throw new IllegalStateException("Cached value is not of required type [" + type.getName() + "]: " + value);
      }
      return (T) value;
    } catch (Exception e) {
      logger.debug(e.getMessage());
      throw toRuntimeException(e);
    }
  }

  @SuppressWarnings("unchecked")
  @Override
  public <T> T get(Object key, Callable<T> valueLoader) {
    String arcusKey = createArcusKey(key);
    Object value;
    try {
      value = getValue(arcusKey);
      if (value != null) {
        return (T) value;
      }
    } catch (Exception e) {
      logger.debug(e.getMessage());
      throw toRuntimeException(e);
    }

    try {
      acquireWriteLockOnKey(arcusKey);
      value = getValue(arcusKey);
      if (value != null) {
        return (T) value;
      } else {
        value = valueLoader.call();
        putValue(arcusKey, value);
        return (T) value;
      }
    } catch (Exception e) {
      logger.debug(e.getMessage());
      throw toRuntimeException(e);
    } finally {
      releaseWriteLockOnKey(arcusKey);
    }
  }

  @Override
  public void put(final Object key, final Object value) {
    try {
      String cacheKey = createArcusKey(key);
      logger.debug("trying to put key: {}, value: {}", cacheKey,
              value != null ? value.getClass().getName() : null);

      if (value == null) {
        logger.info("arcus cannot put NULL value. key: {}, value: {}",
                key.toString(), value);
        return;
      }

      Future<Boolean> future;

      if (operationTranscoder != null) {
        future = arcusClient.set(cacheKey, expireSeconds, value,
                operationTranscoder);
      } else {
        future = arcusClient.set(cacheKey, expireSeconds, value);
      }

      boolean success = future.get(timeoutMilliSeconds,
              TimeUnit.MILLISECONDS);

      if (logger.isDebugEnabled() && !success) {
        logger.debug("failed to put a key: {}, value: {}",
                key.toString(), value);
      }
    } catch (Exception e) {
      logger.info("error: {}, with value: {}", e.getMessage(), value);
      if (wantToGetException) {
        throw new RuntimeException(e);
      }
    }
  }

  /**
   * @param key key
   * @param value value
   * @return 지정된 키에 대한 캐시 아이템이 존재하지 않았으며 지정된 값을 캐시에 저장하였다면 null을 리턴,
   * 캐시 아이템이 이미 존재하였다면 지정된 키에 대한 값을 불러와 리턴한다. 값을 불러올 때 비원자적으로
   * 수행되기 때문에 중간에 다른 캐시 연산 수행으로 인하여 새로운 값이 리턴 될 수 있으며 혹은 캐시 만료로 인해
   * ValueWrapper의 내부 value가 null이 되어 리턴될 수 있다.
   */
  @Override
  public ValueWrapper putIfAbsent(Object key, Object value) {
    try {
      String arcusKey = createArcusKey(key);
      logger.debug("trying to add key: {}, value: {}", arcusKey,
              value != null ? value.getClass().getName() : null);

      if (value == null) {
        throw new IllegalArgumentException("arcus cannot add NULL value. key: " +
                arcusKey);
      }

      Future<Boolean> future;

      if (operationTranscoder != null) {
        future = arcusClient.add(arcusKey, expireSeconds, value,
                operationTranscoder);
      } else {
        future = arcusClient.add(arcusKey, expireSeconds, value);
      }

      boolean added = future.get(timeoutMilliSeconds,
              TimeUnit.MILLISECONDS);

      return added ? null : new SimpleValueWrapper(getValue(arcusKey)); // FIXME: maybe returned with a different value.
    } catch (Exception e) {
      logger.debug(e.getMessage());
      throw toRuntimeException(e);
    }
  }

  @Override
  public void evict(final Object key) {
    try {
      String cacheKey = createArcusKey(key);
      if (logger.isDebugEnabled()) {
        logger.debug("evicting a key: {}", cacheKey);
      }

      Future<Boolean> future = arcusClient.delete(cacheKey);

      boolean success = future.get(timeoutMilliSeconds,
              TimeUnit.MILLISECONDS);

      if (logger.isDebugEnabled() && !success) {
        logger.debug("failed to evivt a key: {}", key.toString());
      }
    } catch (Exception e) {
      logger.info(e.getMessage());
      if (wantToGetException) {
        throw new RuntimeException(e);
      }
    }
  }

  @Override
  public void clear() {
    try {
      String prefixName = (prefix != null) ? prefix : name;
      if (logger.isDebugEnabled()) {
        logger.debug("evicting every key that uses the name: {}",
                prefixName);
      }

      OperationFuture<Boolean> future = arcusClient.flush(serviceId
              + prefixName);

      boolean success = future.get(timeoutMilliSeconds,
              TimeUnit.MILLISECONDS);

      if (logger.isDebugEnabled() && !success) {
        logger.debug(
                "failed to evicting every key that uses the name: {}",
                prefixName);
      }
    } catch (Exception e) {
      logger.info(e.getMessage());
      if (wantToGetException) {
        throw new RuntimeException(e);
      }
    }
  }

  /**
   * serviceId, prefix, name 값을 사용하여 아커스 키를 생성합니다. serviceId는 필수값이며, prefix 또는
   * name 둘 중에 하나가 반드시 있어야 합니다. name과 prefix값이 모두 있다면 prefix 값을 사용합니다.
   * <p>
   * 키 생성 로직은 다음과 같습니다.
   * <p>
   * serviceId + (prefix | name) + ":" + key.toString();
   * <p>
   * 만약 전체 키의 길이가 250자를 넘을 경우에는 key.toString() 대신 그 값을 MD5로 압축한 값을 사용합니다.
   *
   * @param key
   * @return
   */
  public String createArcusKey(final Object key) {
    Assert.notNull(key);
    String keyString, arcusKey;

    if (key instanceof ArcusStringKey) {
      keyString = ((ArcusStringKey) key).getStringKey().replace(' ', '_') +
              String.valueOf(((ArcusStringKey) key).getHash());
    } else if (key instanceof Integer) {
      keyString = key.toString();
    } else {
      keyString = key.toString();
      int hash = ArcusStringKey.light_hash(keyString);
      keyString = keyString.replace(' ', '_') + String.valueOf(hash);
    }

    arcusKey = serviceId + name + ":" + keyString;
    if (this.prefix != null) {
      arcusKey = serviceId + prefix + ":" + keyString;
    }
    if (arcusKey.length() > 250) {
      String digestedString = DigestUtils.md5DigestAsHex(keyString
              .getBytes());
      arcusKey = serviceId + name + ":" + digestedString;
      if (this.prefix != null) {
        arcusKey = serviceId + prefix + ":" + digestedString;
      }
    }
    return arcusKey;
  }

  public void setName(String name) {
    this.name = name;
  }

  public void setExpireSeconds(int expireSeconds) {
    this.expireSeconds = expireSeconds;
  }

  public void setTimeoutMilliSeconds(long timeoutMilliseconds) {
    this.timeoutMilliSeconds = timeoutMilliseconds;
  }

  public void setArcusClient(ArcusClientPool arcusClient) {
    this.arcusClient = arcusClient;
  }

  @Override
  public void afterPropertiesSet() throws Exception {
    if (name == null && prefix == null) {
      throw new IllegalArgumentException(
              "ArcusCache's 'name' or 'prefix' property must have a value.");
    }
    Assert.notNull(serviceId,
            "ArcusCache's serviceId property must have a value.");
  }

  public String getServiceId() {
    return serviceId;
  }

  public void setServiceId(String serviceId) {
    this.serviceId = serviceId;
  }

  @Deprecated
  public boolean isWantToGetException() {
    return wantToGetException;
  }

  @Deprecated
  public void setWantToGetException(boolean wantToGetException) {
    this.wantToGetException = wantToGetException;
  }

  public int getExpireSeconds() {
    return expireSeconds;
  }

  public long getTimeoutMilliSeconds() {
    return timeoutMilliSeconds;
  }

  public ArcusClientPool getArcusClient() {
    return arcusClient;
  }

  public Transcoder<Object> getOperationTranscoder() {
    return operationTranscoder;
  }

  public void setOperationTranscoder(Transcoder<Object> operationTranscoder) {
    this.operationTranscoder = operationTranscoder;
  }

  public String getPrefix() {
    return prefix;
  }

  public void setPrefix(String prefix) {
    this.prefix = prefix;
  }

  public KeyLockProvider getKeyLockProvider() {
    return keyLockProvider;
  }

  public void setKeyLockProvider(KeyLockProvider keyLockProvider) {
    this.keyLockProvider = keyLockProvider;
  }

  private void acquireWriteLockOnKey(String arcusKey) {
    keyLockProvider.getLockForKey(arcusKey).writeLock().lock();
  }

  private void releaseWriteLockOnKey(String arcusKey) {
    keyLockProvider.getLockForKey(arcusKey).writeLock().unlock();
  }

  private RuntimeException toRuntimeException(Exception e) {
    if (e instanceof RuntimeException) {
      return (RuntimeException) e;
    } else {
      return new RuntimeException(e);
    }
  }

  private ValueWrapper toValueWrapper(Object value) {
    return (value != null ? new SimpleValueWrapper(value) : null);
  }

  private Object getValue(String arcusKey) throws Exception {
    logger.debug("getting value by key: {}", arcusKey);

    Future<Object> future;

    // operation transcoder can't be null.
    if (operationTranscoder != null) {
      future = arcusClient.asyncGet(arcusKey, operationTranscoder);
    } else {
      future = arcusClient.asyncGet(arcusKey);
    }

    return future.get(timeoutMilliSeconds, TimeUnit.MILLISECONDS);
  }

  private void putValue(String arcusKey, Object value) throws Exception {
    logger.debug("trying to put key: {}, value: {}", arcusKey,
            value != null ? value.getClass().getName() : null);

    if (value == null) {
      throw new IllegalArgumentException("arcus cannot put NULL value. key: " +
              arcusKey);
    }

    Future<Boolean> future;

    if (operationTranscoder != null) {
      future = arcusClient.set(arcusKey, expireSeconds, value,
              operationTranscoder);
    } else {
      future = arcusClient.set(arcusKey, expireSeconds, value);
    }

    boolean success = future.get(timeoutMilliSeconds,
            TimeUnit.MILLISECONDS);

    if (logger.isDebugEnabled() && !success) {
      logger.debug("failed to put a key: {}, value: {}",
              arcusKey, value);
    }
  }
}
