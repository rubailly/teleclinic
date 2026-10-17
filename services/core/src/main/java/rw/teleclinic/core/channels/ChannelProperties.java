package rw.teleclinic.core.channels;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** @param gatewayKey shared with the USSD gateway (internal network only; production uses mTLS) */
@ConfigurationProperties("teleclinic.channels")
record ChannelProperties(String gatewayKey) {
}
