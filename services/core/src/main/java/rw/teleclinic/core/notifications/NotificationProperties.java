package rw.teleclinic.core.notifications;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** @param smsUrl the aggregator's SMS API (the USSD gateway stands in for it in the demo) */
@ConfigurationProperties("teleclinic.notifications")
record NotificationProperties(String smsUrl, String smsKey) {
}
