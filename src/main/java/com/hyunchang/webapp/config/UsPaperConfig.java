package com.hyunchang.webapp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.webapp.service.paper.UsPaperMarketFeed;
import com.hyunchang.webapp.service.paper.UsPaperYahooFeedService;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "us-paper.enabled", havingValue = "true")
public class UsPaperConfig {
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock usPaperClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean(UsPaperMarketFeed.class)
    UsPaperMarketFeed usPaperMarketFeed(ObjectMapper mapper, Clock clock) {
        return new UsPaperYahooFeedService(mapper, clock);
    }
}
