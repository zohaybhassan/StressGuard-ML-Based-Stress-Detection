import type { User } from "@supabase/supabase-js";

type AuthProviderUser = Pick<User, "app_metadata" | "identities">;

/**
 * Passwordless providers such as Google are already a complete authentication
 * method. They must not be sent through the optional local-password setup flow.
 */
export function hasExternalAuthProvider(user: AuthProviderUser) {
  const metadataProviders = Array.isArray(user.app_metadata.providers)
    ? user.app_metadata.providers
    : [];
  const providers = [
    user.app_metadata.provider,
    ...metadataProviders,
    ...(user.identities ?? []).map((identity) => identity.provider),
  ];

  return providers.some(
    (provider) => typeof provider === "string" && provider.length > 0 && provider !== "email",
  );
}
