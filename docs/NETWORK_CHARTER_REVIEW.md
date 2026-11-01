# Network execution

Direct traffic uses checked public addresses, network-bound DNS/socket factories, bounded manual redirects, representation validation and explicit cleartext opt-in. Native HTTPS tunnels use random session authentication and public-IP pinning without replacing TLS certificates.

NetworkRecovery registers all observers before opening transport and observes the original bound network after default-route handover. Registration failures clean up prior callbacks. Only acquisition/policy interruptions receive the bounded retry; user cancellation, authentication and storage failures are not silently retried.

After15seconds without a permitted network the foreground worker stops, preserving the exact unpaused lease and partial. One persisted JobScheduler request follows selected network/time conditions. Its short job can post a Resume reminder that opens the app; it never starts a foreground-service transfer from a background job. Android controls timing. Notification denial preserves the task and in-app resume; force-stop requires opening the app.

The callback stress regression retains105subscriptions in seven batches and observes the actual ConnectivityService-owned UID request IDs returning to a stable baseline. Android10 unregister posts asynchronous service work, so a return alone is not a server-side cleanup acknowledgement. No registration error is skipped or retried by the test. All API29–36 source results are recorded in [verification](VERIFICATION.md); real OEM/Wi-Fi/cellular transitions remain separate from deterministic guard tests.
