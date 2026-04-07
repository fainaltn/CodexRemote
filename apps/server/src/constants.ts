/**
 * Well-known identifier for the single local host in Phase 1.
 *
 * All session routes validate `hostId` against this value.
 * In Phase 2 (multi-host), this becomes one entry in a host registry.
 */
export const LOCAL_HOST_ID = "local";
