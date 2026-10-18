/**
 * The EMR side of the sync gateway (design 4.5.2). Outbound: a durable queue the gateway (OpenFn) pulls, with retry,
 * backoff and dead letters, so writes wait while a facility EMR is offline. Inbound: changes the gateway found in the EMR;
 * an unchanged content hash is an echo of TeleClinic's own write and is dropped. The ledger keeps ids, versions and
 * hashes per record. Unknown EMR records go to identity matching.
 */
@org.springframework.modulith.ApplicationModule(displayName = "EMR sync")
package rw.teleclinic.core.emrsync;
