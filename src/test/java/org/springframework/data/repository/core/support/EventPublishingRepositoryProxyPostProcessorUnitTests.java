/*
 * Copyright 2016-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.repository.core.support;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import lombok.Getter;
import lombok.Value;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.UUID;

import org.aopalliance.aop.Advice;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.DomainEvents;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.core.RepositoryInformation;
import org.springframework.data.repository.core.support.EventPublishingRepositoryProxyPostProcessor.EventPublishingMethod;
import org.springframework.data.repository.core.support.EventPublishingRepositoryProxyPostProcessor.EventPublishingMethodInterceptor;

/**
 * Unit tests for {@link EventPublishingRepositoryProxyPostProcessor} and contained classes.
 * 
 * @author Oliver Gierke
 * @author Mark Paluch
 * @soundtrack Henrik Freischlader Trio - Nobody Else To Blame (Openness)
 */
@RunWith(MockitoJUnitRunner.class)
public class EventPublishingRepositoryProxyPostProcessorUnitTests {

	@Mock ApplicationEventPublisher publisher;
	@Mock MethodInvocation invocation;

	@Test(expected = IllegalArgumentException.class) // DATACMNS-928
	public void rejectsNullAggregateTypes() {
		EventPublishingMethod.of(null);
	}

	@Test // DATACMNS-928
	public void publishingEventsForNullIsNoOp() {
		EventPublishingMethod.of(OneEvent.class).publishEventsFrom(null, publisher);
	}

	@Test // DATACMNS-928
	public void exposesEventsExposedByEntityToPublisher() {

		SomeEvent first = new SomeEvent();
		SomeEvent second = new SomeEvent();
		MultipleEvents entity = MultipleEvents.of(Arrays.asList(first, second));

		EventPublishingMethod.of(MultipleEvents.class).publishEventsFrom(entity, publisher);

		verify(publisher).publishEvent(eq(first));
		verify(publisher).publishEvent(eq(second));
	}

	@Test // DATACMNS-928
	public void exposesSingleEventByEntityToPublisher() {

		SomeEvent event = new SomeEvent();
		OneEvent entity = OneEvent.of(event);

		EventPublishingMethod.of(OneEvent.class).publishEventsFrom(entity, publisher);

		verify(publisher, times(1)).publishEvent(event);
	}

	@Test // DATACMNS-928
	public void doesNotExposeNullEvent() {

		OneEvent entity = OneEvent.of(null);

		EventPublishingMethod.of(OneEvent.class).publishEventsFrom(entity, publisher);

		verify(publisher, times(0)).publishEvent(any());
	}

	@Test // DATACMNS-928
	public void doesNotCreatePublishingMethodIfNoAnnotationDetected() {
		assertThat(EventPublishingMethod.of(Object.class)).isNull();
	}

	@Test // DATACMNS-928
	public void interceptsSaveMethod() throws Throwable {

		doReturn(SampleRepository.class.getMethod("save", Object.class)).when(invocation).getMethod();

		SomeEvent event = new SomeEvent();
		MultipleEvents sample = MultipleEvents.of(Collections.singletonList(event));
		doReturn(new Object[] { sample }).when(invocation).getArguments();

		EventPublishingMethodInterceptor//
				.of(EventPublishingMethod.of(MultipleEvents.class), publisher)//
				.invoke(invocation);

		verify(publisher).publishEvent(event);
	}

	@Test // DATACMNS-928
	public void doesNotInterceptNonSaveMethod() throws Throwable {

		doReturn(SampleRepository.class.getMethod("findById", Object.class)).when(invocation).getMethod();

		EventPublishingMethodInterceptor//
				.of(EventPublishingMethod.of(MultipleEvents.class), publisher)//
				.invoke(invocation);

		verify(publisher, never()).publishEvent(any());
	}

	@Test // DATACMNS-928
	public void registersAdviceIfDomainTypeExposesEvents() {

		RepositoryInformation information = new DummyRepositoryInformation(SampleRepository.class);
		RepositoryProxyPostProcessor processor = new EventPublishingRepositoryProxyPostProcessor(publisher);

		ProxyFactory factory = mock(ProxyFactory.class);

		processor.postProcess(factory, information);

		verify(factory).addAdvice(any(EventPublishingMethodInterceptor.class));
	}

	@Test // DATACMNS-928
	public void doesNotAddAdviceIfDomainTypeDoesNotExposeEvents() {

		RepositoryInformation information = new DummyRepositoryInformation(CrudRepository.class);
		RepositoryProxyPostProcessor processor = new EventPublishingRepositoryProxyPostProcessor(publisher);

		ProxyFactory factory = mock(ProxyFactory.class);

		processor.postProcess(factory, information);

		verify(factory, never()).addAdvice(any(Advice.class));
	}

	@Test // DATACMNS-928
	public void publishesEventsForCallToSaveWithIterable() throws Throwable {

		SomeEvent event = new SomeEvent();
		MultipleEvents sample = MultipleEvents.of(Collections.singletonList(event));
		doReturn(new Object[] { Collections.singletonList(sample) }).when(invocation).getArguments();

		doReturn(SampleRepository.class.getMethod("saveAll", Iterable.class)).when(invocation).getMethod();

		EventPublishingMethodInterceptor//
				.of(EventPublishingMethod.of(MultipleEvents.class), publisher)//
				.invoke(invocation);

		verify(publisher).publishEvent(any(SomeEvent.class));
	}

	@Test // DATACMNS-975
	public void publishesEventsAfterSaveInvocation() throws Throwable {

		doThrow(new IllegalStateException()).when(invocation).proceed();

		try {
			EventPublishingMethodInterceptor//
					.of(EventPublishingMethod.of(OneEvent.class), publisher)//
					.invoke(invocation);
		} catch (IllegalStateException o_O) {
			verify(publisher, never()).publishEvent(any(SomeEvent.class));
		}
	}

	@Value(staticConstructor = "of")
	static class MultipleEvents {
		@Getter(onMethod = @__(@DomainEvents)) Collection<? extends Object> events;
	}

	@Value(staticConstructor = "of")
	static class OneEvent {
		@Getter(onMethod = @__(@DomainEvents)) Object event;
	}

	@Value
	static class SomeEvent {
		UUID id = UUID.randomUUID();
	}

	interface SampleRepository extends CrudRepository<MultipleEvents, Long> {}
}
