# Dashboard data notes

The dashboard reads the authenticated user from the Supabase server client and
adds an explicit `user_id` filter to every health-data query. Supabase Row Level
Security remains the final ownership boundary. No service-role key is used.

The browser stores only its IANA time-zone name in a first-party cookie. The
server uses that value to calculate the exact start and end of the user's local
day, including daylight-saving changes. Missing health values remain missing and
are never converted to zero.

Reading freshness is based on `stress_predictions.recorded_at`. A reading is
fresh for 45 minutes, allowing the Android worker's 30-minute synchronization
cadence plus scheduling delay. Older readings are delayed; an absent or invalid
timestamp is unavailable.

The current schema does not persist a sync-run record. The UI therefore reports
the newest cloud record creation time rather than claiming that it knows when a
complete synchronization run succeeded.

Authenticated Playwright checks require a deliberately created test account and
storage-state file. Set `E2E_AUTH_STORAGE_STATE` for a general authenticated
account and `E2E_EMPTY_ACCOUNT_STORAGE_STATE` for an account with no synchronized
health rows. Tests skip safely when those files are not provided.
