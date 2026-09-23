package com.seatlock.auth;

import com.seatlock.user.Role;

/** The authenticated caller, rebuilt from the JWT on every request (no DB lookup). */
public record AuthUser(Long id, String email, Role role) {}
