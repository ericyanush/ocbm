# Native CarPlay GPS forwarding

GPS forwarding is opt-in under Vehicle location. Grant fine-location permission,
enable forwarding, and restart the CarPlay session so Identify advertises it. The
main app reads the vehicle GPS provider, builds GPGGA/GPRMC sentences, and sends
only the types requested by the phone over the native Wi-Fi iAP2 tunnel. Location
data is not forwarded through the adapter. MFi certificate/signature operations
still use the adapter.

When enabled, the native Identify contains LocationInformationComponent plus
0xFFFB in MessagesSentByAccessory and 0xFFFA/0xFFFC in
MessagesReceivedFromDevice. If an Identify retry strips the GPS component, its
optional message declarations are stripped too. Disabled sessions retain the
existing Identify message lists.

The main app caches a structurally validated X.509/PKCS#7 certificate for each
subscribed adapter binding. Normal and fast tunnel reads share that cache; a
rebind creates a fresh cache, and retired bindings cannot return cached data.
Signatures are always obtained for the individual challenge. The phone still
performs certificate trust validation and MFi authentication.

Each `[GNSS] fix` diagnostic now includes a `forwarding` JSON object and native
control generation. These lines use the existing exported rolling log:

- `reason`: `disabled`, `tunnel_unavailable`, `tunnel_busy`,
  `tunnel_not_identified`, `awaiting_subscription`, `no_requested_sentence`,
  `transport_unavailable`, `partially_sent`, or `sent`.
- `tunnelState` and `linkUp`: distinguish an absent tunnel, link synchronization,
  authentication, Identify, and a ready tunnel.
- `requestedGga` / `requestedRmc`: the phone's current requested sentence types.
- `sentSentences`: frames accepted by the active iAP2 sender for this fix; this
  does not prove the phone used the location.
- `lastHandshakeError`: records certificate/signature failures until the
  handshake recovers or the native session is reset.

An initial `sent=false` is expected before the tunnel is identified and the phone
subscribes. Persistent false values now indicate which condition is missing.
`tunnel_busy` skips a fix rather than blocking the GPS callback behind an ongoing
authentication operation. The report itself contains no NMEA or coordinates;
the existing fix log separately includes the provider's coordinates and accuracy.

## Deployment and validation

Update the main AAOS app, including its rebuilt JNI library (Gradle builds it).
These GPS changes require no adapter or USB-helper update.

Test with GPS forwarding enabled and a newly started native CarPlay session.
Export diagnostics after the phone has connected and a navigation app has run.
Look for a certificate cache fill followed by a cache hit, then tunnel state
`Identified`, nonzero requested sentence flags, and `sentSentences` above zero.
Repeat after switching phones to verify the native control generation and
subscription state change. Turning forwarding off must stop GPS collection;
restart the session to remove GPS from its Identify capability advertisement.
