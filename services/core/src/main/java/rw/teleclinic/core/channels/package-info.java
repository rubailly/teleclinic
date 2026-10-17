/**
 * Channels module: the core's API for the USSD (and later voice) gateway. Same booking, consent and payment as the
 * web, reached with a phone number, a PIN and a short-lived channel token instead of a browser session.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Channels")
package rw.teleclinic.core.channels;
