package com.secrux.config

import javax.sql.DataSource
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy

@Configuration
class JooqConfig {

    @Bean
    @Primary
    fun dslContext(dataSource: DataSource): DSLContext {
        val txAwareDataSource = TransactionAwareDataSourceProxy(dataSource)
        return DSL.using(txAwareDataSource, SQLDialect.POSTGRES)
    }
}

