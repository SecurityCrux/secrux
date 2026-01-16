package com.secrux.gateway

import com.fasterxml.jackson.databind.ObjectMapper
import com.secrux.config.ExecutorGatewayProperties
import com.secrux.repo.TaskRepository
import com.secrux.service.ExecutorService
import com.secrux.service.ExecutorSessionRegistry
import com.secrux.service.TaskLogService
import com.secrux.service.TaskResultService
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelInitializer
import io.netty.channel.EventLoopGroup
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.LengthFieldBasedFrameDecoder
import io.netty.handler.codec.LengthFieldPrepender
import io.netty.handler.codec.string.StringDecoder
import io.netty.handler.codec.string.StringEncoder
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.SelfSignedCertificate
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.io.File
import java.nio.charset.StandardCharsets

@Component
@ConditionalOnProperty(prefix = "executor.gateway", name = ["enabled"], havingValue = "true")
class ExecutorGatewayServer(
    private val props: ExecutorGatewayProperties,
    private val objectMapper: ObjectMapper,
    private val executorService: ExecutorService,
    private val sessionRegistry: ExecutorSessionRegistry,
    private val taskRepository: TaskRepository,
    private val taskLogService: TaskLogService,
    private val taskResultService: TaskResultService
) {

    private val log = LoggerFactory.getLogger(ExecutorGatewayServer::class.java)
    private var bossGroup: EventLoopGroup? = null
    private var workerGroup: EventLoopGroup? = null
    private var channel: Channel? = null

    @PostConstruct
    fun start() {
        bossGroup = NioEventLoopGroup(1)
        workerGroup = NioEventLoopGroup()
        val sslContext = buildSslContext()
        val bootstrap = ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel::class.java)
            .childHandler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    val pipeline = ch.pipeline()
                    pipeline.addLast(sslContext.newHandler(ch.alloc()))
                    pipeline.addLast(LengthFieldBasedFrameDecoder(props.maxFrameBytes, 0, 4, 0, 4))
                    pipeline.addLast(LengthFieldPrepender(4))
                    pipeline.addLast(StringDecoder(StandardCharsets.UTF_8))
                    pipeline.addLast(StringEncoder(StandardCharsets.UTF_8))
                    pipeline.addLast(
                        ExecutorGatewayHandler(
                            objectMapper,
                            executorService,
                            sessionRegistry,
                            taskRepository,
                            taskLogService,
                            taskResultService
                        )
                    )
                }
            })
        channel = bootstrap.bind(props.port).sync().channel()
        log.info("event=executor_gateway_started port={}", props.port)
    }

    @PreDestroy
    fun stop() {
        channel?.close()
        workerGroup?.shutdownGracefully()
        bossGroup?.shutdownGracefully()
    }

    private fun buildSslContext(): SslContext {
        val certPath = props.certificatePath.trim().takeIf { it.isNotBlank() }
        val keyPath = props.privateKeyPath.trim().takeIf { it.isNotBlank() }
        return try {
            if (certPath != null && keyPath != null) {
                val certFile = File(certPath)
                val keyFile = File(keyPath)
                if (certFile.isFile && keyFile.isFile) {
                    log.info("event=executor_gateway_tls_configured mode=file")
                    return SslContextBuilder.forServer(certFile, keyFile).build()
                }
                log.warn("event=executor_gateway_tls_cert_missing fallback=self_signed")
            }
            val cert = SelfSignedCertificate()
            log.info("event=executor_gateway_tls_configured mode=self_signed")
            SslContextBuilder.forServer(cert.certificate(), cert.privateKey()).build()
        } catch (ex: Exception) {
            log.error("event=executor_gateway_tls_init_failed", ex)
            throw IllegalStateException("Failed to initialize executor gateway TLS", ex)
        }
    }
}
