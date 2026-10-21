package com.ezam.service;

import com.ezam.dao.SqlErrors;
import com.ezam.dao.TeacherDAO;
import com.ezam.dto.AuthDTO.*;
import com.ezam.model.GiaoVien;
import com.ezam.util.*;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

/** Auth business logic and transaction boundary, independent of Servlet state. */
public final class AuthService {
    private static final String DUMMY_HASH = PasswordUtil.hashPassword("teacher-auth-dummy-password");
    private final TransactionManager transactions;
    private final TeacherDAO teachers;
    private final JwtUtil jwt;

    public AuthService(TransactionManager transactions, TeacherDAO teachers, JwtUtil jwt) {
        this.transactions = Objects.requireNonNull(transactions);
        this.teachers = Objects.requireNonNull(teachers); this.jwt = Objects.requireNonNull(jwt);
    }
    public TeacherResponse register(Register payload) {
        try {
            return transactions.execute(c -> {
                if (teachers.findByEmail(c, payload.email()).isPresent()) throw new AuthException(409, "Email already registered.");
                var teacher = new GiaoVien(); teacher.setIdGv(UUID.randomUUID().toString());
                teacher.setEmail(payload.email()); teacher.setMatKhau(PasswordUtil.hashPassword(payload.mat_khau()));
                teacher.setHoTen(payload.ho_ten()); teacher.setTruong(payload.truong()); teacher.setTinh(payload.tinh());
                teachers.insert(c, teacher);
                return TeacherResponse.from(teacher); // validate safe output before COMMIT
            });
        } catch (SQLException failure) {
            if (SqlErrors.isEmailConflict(failure)) throw new AuthException(409, "Email already registered.");
            throw new AuthException(503, "Unable to register teacher.");
        }
    }
    public TokenResponse login(Login payload) {
        GiaoVien teacher;
        try { teacher = transactions.execute(c -> teachers.findByEmail(c, payload.email()).orElse(null)); }
        catch (SQLException failure) { throw new AuthException(503, "Authentication database unavailable."); }
        boolean valid = PasswordUtil.verifyPassword(payload.mat_khau(), teacher == null ? DUMMY_HASH : teacher.getMatKhau());
        if (teacher == null || !valid) throw new AuthException(401, "Incorrect email or password.");
        return new TokenResponse(jwt.createAccessToken(teacher.getIdGv()));
    }
    public TeacherResponse me(String authorization) {
        String teacherId = jwt.teacherIdFromAuthorization(authorization);
        try {
            return transactions.execute(c -> TeacherResponse.from(teachers.findById(c, teacherId).orElseThrow(AuthException::credentials)));
        } catch (SQLException failure) { throw new AuthException(503, "Authentication database unavailable."); }
    }
    public TeacherResponse updateTeacherProfile(String teacherId, Profile payload) {
        try {
            return transactions.execute(c -> {
                var teacher = teachers.lockById(c, teacherId).orElseThrow(() -> new AuthException(404, "Teacher account not found."));
                teacher.setHoTen(payload.ho_ten()); teacher.setTruong(payload.truong()); teacher.setTinh(payload.tinh());
                if (!teachers.updateProfile(c, teacherId, teacher.getHoTen(), teacher.getTruong(), teacher.getTinh()))
                    throw new SQLException("Profile update failed.");
                return TeacherResponse.from(teacher); // validation failure rolls back too
            });
        } catch (SQLException failure) { throw new AuthException(503, "Unable to update teacher profile."); }
    }
    public PasswordResponse changeTeacherPassword(String teacherId, ChangePassword payload) {
        try {
            return transactions.execute(c -> {
                var teacher = teachers.lockById(c, teacherId).orElseThrow(() -> new AuthException(404, "Teacher account not found."));
                if (!PasswordUtil.verifyPassword(payload.mat_khau_cu(), teacher.getMatKhau()))
                    throw new AuthException(400, "Mật khẩu hiện tại không chính xác.");
                String hash = PasswordUtil.hashPassword(payload.mat_khau_moi());
                if (!teachers.updatePasswordHash(c, teacherId, hash)) throw new SQLException("Password update failed.");
                return new PasswordResponse();
            });
        } catch (SQLException failure) { throw new AuthException(503, "Unable to change teacher password."); }
    }
}
