import { User, UserManager, WebStorageStateStore } from "oidc-client-ts";

const origin = window.location.origin;

export const userManager = new UserManager({
  authority: import.meta.env.VITE_OIDC_AUTHORITY ?? "http://localhost:18180/realms/uaevat",
  client_id: import.meta.env.VITE_OIDC_CLIENT_ID ?? "vat-web",
  redirect_uri: `${origin}/auth/callback`,
  post_logout_redirect_uri: origin,
  response_type: "code",
  scope: "openid profile email",
  // Tokens stay in session storage so they do not outlive the tab.
  userStore: new WebStorageStateStore({ store: window.sessionStorage }),
  automaticSilentRenew: false,
});

export async function currentUser(): Promise<User | null> {
  const user = await userManager.getUser();
  return user && !user.expired ? user : null;
}
