package org.example.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Yöneticinin yeni kullanıcı açarken gönderdiği gövde.
 *
 * @param temporary true ise kullanıcı ilk girişte şifresini değiştirmeye
 *                  zorlanır — yöneticinin belirlediği şifre kalıcı olmaz.
 * @param role      ADMIN | MODERATOR | VIEWER. Bir kullanıcının tek rolü olur.
 */
public record CreateUserRequest(
    @NotBlank @Size(min = 3, max = 64) String username,
    @NotBlank @Email @Size(max = 255) String email,
    @Size(max = 128) String firstName,
    @Size(max = 128) String lastName,
    @NotBlank @Size(min = 8, max = 128) String password,
    boolean temporary,
    @NotBlank String role
) {
}
