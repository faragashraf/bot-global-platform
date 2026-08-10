export interface AuthenticatedUser {
  readonly id: string;
  readonly userName: string;
  readonly email: string;
  readonly displayName: string;
  readonly roles: readonly string[];
}

export interface LoginRequest {
  readonly userNameOrEmail: string;
  readonly password: string;
  readonly rememberMe: boolean;
}

export const ADMINISTRATOR_ROLE = 'Administrator';
