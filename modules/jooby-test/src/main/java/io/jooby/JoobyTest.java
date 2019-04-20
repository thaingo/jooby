/**
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
 *
 * Copyright 2014 Edgar Espina
 */
package io.jooby;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Startup Jooby applications using JUnit extension mechanism.
 *
 * When this annotation is set at class level, a single jooby application is started:
 *
 * <pre>{@code
 * &#64;JoobyTest(MyApp.class)
 * public class MyTest {
 *
 *   &#64;Test
 *   public void test() {
 *     Use your favorite HTTP client and call Jooby.
 *   }
 * }
 * }</pre>
 *
 * When this annotation is set at method level, a jooby application is started per test/method:
 *
 * <pre>{@code
 *
 * public class MyTest {
 *
 *   &#64;JoobyTest(MyApp.class)
 *   public void test() {
 *     Use your favorite HTTP client and call Jooby.
 *   }
 *
 *   &#64;JoobyTest(MyApp.class)
 *   public void anotherTest() {
 *     Use your favorite HTTP client and call Jooby.
 *   }
 * }
 * }</pre>
 *
 * Server path and port can be injected as instance fields or method parameters:
 *
 * <pre>{@code
 *
 * &#64;JoobyTest(MyApp.class)
 * public MyTest {
 *
 *   public String serverPath;
 *
 *   public void test() {
 *     Use your favorite HTTP client and call Jooby.
 *   }
 * }
 * }</pre>
 *
 * <pre>{@code
 * public MyTest {
 *
 *   &#64;JoobyTest(MyApp.class)
 *   public void test(String serverPath) {
 *     Use your favorite HTTP client and call Jooby.
 *   }
 * }
 * }</pre>
 *
 * @author edgar
 * @since 2.0.0
 */
@Target({TYPE, METHOD})
@Retention(RUNTIME)
@Test
@ExtendWith(JoobyExtension.class)
public @interface JoobyTest {
  /**
   * Application class. Required.
   *
   * @return Application class.
   */
  Class<? extends Jooby> value();

  /**
   * Application environment, default is <code>test</code>.
   *
   * @return Application environment, default is <code>test</code>.
   */
  String environment() default "test";

  /**
   * Server port. At class level default port is <code>8911</code>. At method level default port
   * is random.
   *
   * @return Server port. At class level default port is <code>8911</code>. At method level default
   *     port is random.
   */
  int port() default -1;

  /**
   * Application execution mode. Default is {@link ExecutionMode#DEFAULT}.
   *
   * @return Application execution mode. Default is {@link ExecutionMode#DEFAULT}.
   */
  ExecutionMode executionMode() default ExecutionMode.DEFAULT;
}
