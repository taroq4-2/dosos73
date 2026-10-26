# Privacy

No accounts, advertising SDKs, analytics or telemetry are implemented. URLs are sent to their destination servers and media extraction may contact the source site's public media hosts. Those services can observe the device IP and requests. The local media proxy does not inspect TLS content or persist request logs.

Clipboard access occurs only after pressing Paste. The selected destination URI is stored locally. Transfers use private temporary files, then MediaStore or a user-authorized folder. Backup is disabled. The project does not collect cookies or passwords, and cannot access private account content.

The operating system and externally selected file-opening apps have their own privacy behavior. Public GitHub bug reports should not contain private links, credentials or personal files.

The waiting queue and failed-download history store source URLs, titles, selected formats and destination URIs in the app's private storage so users can review or retry tasks. Saved-file records also store local output URIs and file metadata. These records are not uploaded to us and are excluded from Android backup. Removing queue/history entries removes their corresponding local records; clearing app storage removes all app-private records but does not automatically delete completed downloads.
