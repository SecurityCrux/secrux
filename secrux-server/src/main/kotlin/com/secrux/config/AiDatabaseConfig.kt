package com.secrux.config

import javax.sql.DataSource
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.flyway.FlywayProperties
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

@Configuration
class AiDatabaseConfig(
    private val environment: Environment
) {

    @Bean
    @ConfigurationProperties("spring.datasource.ai")
    fun aiDataSourceProperties(): DataSourceProperties = DataSourceProperties()

    @Bean
    fun aiDataSource(@Qualifier("aiDataSourceProperties") props: DataSourceProperties): DataSource =
        props.initializeDataSourceBuilder().build()

    @Bean
    fun aiFlywayInitializer(
        @Qualifier("aiDataSource") dataSource: DataSource
    ): InitializingBean =
        InitializingBean {
            val props = Binder.get(environment)
                .bind("spring.flyway.ai", FlywayProperties::class.java)
                .orElseGet { FlywayProperties() }
            val locations = props.locations?.toTypedArray() ?: arrayOf("classpath:db/ai")
            val schemas = props.schemas?.toTypedArray()
            val config = Flyway.configure()
                .dataSource(dataSource)
                .locations(*locations)
                .baselineOnMigrate(props.isBaselineOnMigrate)
                .baselineVersion(
                    MigrationVersion.fromVersion(props.baselineVersion ?: "1")
                )
            props.table?.let { config.table(it) }
            schemas?.let { config.schemas(*it) }
            props.defaultSchema?.let { config.defaultSchema(it) }
            config.load().migrate()
        }

    @Bean
    fun aiDslContext(@Qualifier("aiDataSource") dataSource: DataSource): DSLContext =
        DSL.using(dataSource, SQLDialect.POSTGRES)
}
