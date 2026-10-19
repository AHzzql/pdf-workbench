export interface AccountUser {
  id: string;
  email: string;
  displayName: string;
}
export interface AccountSession {
  authenticated: boolean;
  user: AccountUser | null;
}
export type AccountPanel = "login" | "register" | "profile" | "security";
