package com.github.lhotari.reactive.pulsar.internal;

import static com.github.lhotari.reactive.pulsar.internal.PulsarFutureAdapter.adaptPulsarFuture;

import com.github.lhotari.reactive.pulsar.adapter.EndOfStreamAction;
import com.github.lhotari.reactive.pulsar.adapter.InstantStartAtSpec;
import com.github.lhotari.reactive.pulsar.adapter.MessageIdStartAtSpec;
import com.github.lhotari.reactive.pulsar.adapter.ReactiveMessageReader;
import com.github.lhotari.reactive.pulsar.adapter.ReactiveReaderAdapter;
import com.github.lhotari.reactive.pulsar.adapter.ReactiveReaderAdapterFactory;
import com.github.lhotari.reactive.pulsar.adapter.ReaderConfigurer;
import com.github.lhotari.reactive.pulsar.adapter.StartAtSpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Reader;
import org.apache.pulsar.client.api.ReaderBuilder;
import org.apache.pulsar.client.api.Schema;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class DefaultReactiveMessageReader<T> implements ReactiveMessageReader<T> {

    private final Schema<T> schema;
    private final ReaderConfigurer<T> readerConfigurer;
    private final String topicName;
    private final ReactiveReaderAdapterFactory reactiveReaderAdapterFactory;
    private final StartAtSpec startAtSpec;
    private final EndOfStreamAction endOfStreamAction;

    public DefaultReactiveMessageReader(
        ReactiveReaderAdapterFactory reactiveReaderAdapterFactory,
        Schema<T> schema,
        ReaderConfigurer<T> readerConfigurer,
        String topicName,
        StartAtSpec startAtSpec,
        EndOfStreamAction endOfStreamAction
    ) {
        this.schema = schema;
        this.readerConfigurer = readerConfigurer;
        this.topicName = topicName;
        this.reactiveReaderAdapterFactory = reactiveReaderAdapterFactory;
        this.startAtSpec = startAtSpec;
        this.endOfStreamAction = endOfStreamAction;
    }

    ReactiveReaderAdapter<T> createReactiveReaderAdapter(StartAtSpec startAtSpec) {
        return reactiveReaderAdapterFactory.create(readerStartingAt(startAtSpec));
    }

    private Function<PulsarClient, ReaderBuilder<T>> readerStartingAt(StartAtSpec startAtSpec) {
        return pulsarClient -> {
            ReaderBuilder<T> readerBuilder = pulsarClient.newReader(schema);
            if (topicName != null) {
                readerBuilder.topic(topicName);
            }
            if (startAtSpec != null) {
                if (startAtSpec instanceof MessageIdStartAtSpec) {
                    MessageIdStartAtSpec messageIdStartAtSpec = (MessageIdStartAtSpec) startAtSpec;
                    readerBuilder.startMessageId(messageIdStartAtSpec.getMessageId());
                    if (messageIdStartAtSpec.isInclusive()) {
                        readerBuilder.startMessageIdInclusive();
                    }
                } else {
                    InstantStartAtSpec instantStartAtSpec = (InstantStartAtSpec) startAtSpec;
                    long rollbackDuration =
                        ChronoUnit.SECONDS.between(instantStartAtSpec.getInstant(), Instant.now()) + 1L;
                    if (rollbackDuration < 0L) {
                        throw new IllegalArgumentException("InstantStartAtSpec must be in the past.");
                    }
                    readerBuilder.startMessageFromRollbackDuration(rollbackDuration, TimeUnit.SECONDS);
                }
            }
            if (readerConfigurer != null) {
                readerConfigurer.configure(readerBuilder);
            }
            return readerBuilder;
        };
    }

    static <T> Mono<Message<T>> readNextMessage(Reader<T> reader, EndOfStreamAction endOfStreamAction) {
        Mono<Message<T>> messageMono = adaptPulsarFuture(reader::readNextAsync);
        if (endOfStreamAction == EndOfStreamAction.COMPLETE) {
            return adaptPulsarFuture(reader::hasMessageAvailableAsync)
                .filter(Boolean::booleanValue)
                .flatMap(__ -> messageMono);
        } else {
            return messageMono;
        }
    }

    @Override
    public Mono<Message<T>> readMessage() {
        return createReactiveReaderAdapter(startAtSpec)
            .usingReader(reader -> readNextMessage(reader, endOfStreamAction));
    }

    @Override
    public Flux<Message<T>> readMessages() {
        return createReactiveReaderAdapter(startAtSpec)
            .usingReaderMany(reader -> {
                Mono<Message<T>> messageMono = readNextMessage(reader, endOfStreamAction);
                if (endOfStreamAction == EndOfStreamAction.COMPLETE) {
                    return messageMono.repeatWhen(flux -> flux.takeWhile(emitted -> emitted > 0L));
                }
                return messageMono.repeat();
            });
    }
}