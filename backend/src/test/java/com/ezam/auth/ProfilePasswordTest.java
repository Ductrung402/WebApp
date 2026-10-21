package com.ezam.auth;

import com.ezam.dto.AuthDTO;
import com.ezam.util.AuthException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ProfilePasswordTest {
    @Test void profileNormalizesOptionalBlankNullAndOmittedFields() {
        var data = AuthDTO.profile("{\"ho_ten\":\"  Nguyễn An  \",\"truong\":\"  \",\"tinh\":null}");
        assertEquals("Nguyễn An", data.ho_ten()); assertNull(data.truong()); assertNull(data.tinh());
        assertNull(AuthDTO.profile("{\"ho_ten\":\"An\"}").truong());
    }
    @ParameterizedTest @ValueSource(strings={"{}", "{\"ho_ten\":null}", "{\"ho_ten\":\" \"}", "{\"ho_ten\":123}",
            "{\"ho_ten\":\"An\",\"email\":\"other@example.com\"}", "{\"ho_ten\":\"An\",\"truong\":false}"})
    void profileRejectsInvalidAndForbiddenFields(String json) { assertEquals(422, assertThrows(AuthException.class, () -> AuthDTO.profile(json)).status()); }
    @Test void passwordsPreserveSpacesAndAreRedacted() {
        var data = AuthDTO.changePassword("{\"mat_khau_cu\":\"  old-secret  \",\"mat_khau_moi\":\"  new-secret  \"}");
        assertEquals("  old-secret  ", data.mat_khau_cu()); assertEquals("  new-secret  ", data.mat_khau_moi());
        assertEquals("ChangePassword[redacted]", data.toString());
        assertEquals("Đã cập nhật mật khẩu.", new AuthDTO.PasswordResponse().message());
    }
    @ParameterizedTest @ValueSource(strings={"{}", "{\"mat_khau_cu\":\"abcdefgh\",\"mat_khau_moi\":\"short\"}",
            "{\"mat_khau_cu\":null,\"mat_khau_moi\":\"abcdefgh\"}",
            "{\"mat_khau_cu\":\"abcdefgh\",\"mat_khau_moi\":\"abcdefgh\",\"id_gv\":\"spoof\"}"})
    void passwordRequestRejectsInvalidAndForbiddenFields(String json) { assertEquals(422, assertThrows(AuthException.class, () -> AuthDTO.changePassword(json)).status()); }
}
