/**
 * Copyright (c) 2020 EmeraldPay, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.emeraldpay.dshackle.upstream.bitcoin

import io.emeraldpay.dshackle.cache.Caches
import io.emeraldpay.dshackle.config.UpstreamsConfig
import io.emeraldpay.dshackle.reader.EmptyReader
import io.emeraldpay.dshackle.reader.Reader
import io.emeraldpay.dshackle.upstream.EmptyHead
import io.emeraldpay.dshackle.upstream.Head
import io.emeraldpay.dshackle.upstream.MergedHead
import io.emeraldpay.dshackle.upstream.Multistream
import io.emeraldpay.dshackle.upstream.RequestPostprocessor
import io.emeraldpay.dshackle.upstream.Selector
import io.emeraldpay.dshackle.upstream.Upstream
import io.emeraldpay.dshackle.upstream.rpcclient.JsonRpcRequest
import io.emeraldpay.dshackle.upstream.rpcclient.JsonRpcResponse
import io.emeraldpay.grpc.Chain
import org.slf4j.LoggerFactory
import org.springframework.context.Lifecycle
import reactor.core.publisher.Mono

@Suppress("UNCHECKED_CAST")
open class BitcoinMultistream(
    chain: Chain,
    val upstreams: MutableList<BitcoinUpstream>,
    caches: Caches
) : Multistream(chain, upstreams as MutableList<Upstream>, caches, RequestPostprocessor.Empty()), Lifecycle {

    companion object {
        private val log = LoggerFactory.getLogger(BitcoinMultistream::class.java)
    }

    private var head: Head = EmptyHead()
    private var esplora = upstreams.find { it.esploraClient != null }?.esploraClient
    private var reader = BitcoinReader(this, head, esplora)
    private var addressActiveCheck: AddressActiveCheck? = null
    private var xpubAddresses: XpubAddresses? = null

    override fun init() {
        if (upstreams.size > 0) {
            head = updateHead()
        }
        super.init()
    }

    open fun getXpubAddresses(): XpubAddresses? {
        return xpubAddresses
    }

    override fun updateHead(): Head {
        head.let {
            if (it is Lifecycle) {
                it.stop()
            }
        }
        lagObserver?.stop()
        lagObserver = null
        val head = if (upstreams.size == 1) {
            val upstream = upstreams.first()
            upstream.setLag(0)
            upstream.getHead()
        } else {
            val newHead = MergedHead(upstreams.map { it.getHead() }).apply {
                this.start()
            }
            val lagObserver = BitcoinHeadLagObserver(newHead, upstreams)
            this.lagObserver = lagObserver
            newHead
        }
        onHeadUpdated(head)
        return head
    }

    override fun getRoutedApi(matcher: Selector.Matcher): Mono<Reader<JsonRpcRequest, JsonRpcResponse>> {
        // TODO
        return Mono.just(EmptyReader())
    }

    open fun getReader(): BitcoinReader {
        return reader
    }

    override fun onUpstreamsUpdated() {
        super.onUpstreamsUpdated()
        esplora = upstreams.find { it.esploraClient != null }?.esploraClient
        reader = BitcoinReader(this, this.head, esplora)
        addressActiveCheck = esplora?.let { AddressActiveCheck(it) }
        xpubAddresses = addressActiveCheck?.let { XpubAddresses(it) }
    }

    override fun setHead(head: Head) {
        this.head = head
        reader = BitcoinReader(this, head, esplora)
    }

    override fun getHead(): Head {
        return head
    }

    override fun getLabels(): Collection<UpstreamsConfig.Labels> {
        return upstreams.flatMap { it.getLabels() }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Upstream> cast(selfType: Class<T>): T {
        if (!selfType.isAssignableFrom(this.javaClass)) {
            throw ClassCastException("Cannot cast ${this.javaClass} to $selfType")
        }
        return this as T
    }

    override fun isRunning(): Boolean {
        return super.isRunning() || reader.isRunning
    }

    override fun start() {
        super.start()
        reader.start()
    }

    override fun stop() {
        super.stop()
        reader.stop()
    }
}
