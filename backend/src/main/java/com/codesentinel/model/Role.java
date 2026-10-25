package com.codesentinel.model;

/**
 * Defines the access roles within CodeSentinel AI.
 * 
 * - USER: Default role for self-registered developers. Can manage their own projects and fixes.
 * - REVIEWER: Peer reviewer invited to specific project versions with review/comment privileges.
 * - ADMIN: System administrator with oversight over platform configurations.
 */
public enum Role {
    USER,
    REVIEWER,
    ADMIN
}
