# Task 3.1 — Room APIs Backend

Đã triển khai ba API trong `backend/app/api/room.py` và đăng ký router ở
`backend/main.py`. Router chỉ nhận DTO, sử dụng dependency và gọi service.

| API | Quyền truy cập | Kết quả |
| --- | --- | --- |
| `POST /api/rooms` | JWT giáo viên, phải sở hữu đề thi | HTTP 201, phòng mới `DANG_CHO` |
| `GET /api/rooms/{id_phong_thi}` | JWT giáo viên sở hữu phòng | HTTP 200, thông tin phòng và `hocSinhList` |
| `POST /api/rooms/join` | Ẩn danh, không cần JWT/password | HTTP 201, thông tin học sinh và phòng |

## Schema Verification Proof

Code đầy đủ: [schemas/room.py](../backend/app/schemas/room.py).

Các thuộc tính Python/ORM dùng snake_case. DTO sử dụng `to_camel` để xuất JSON
camelCase theo `.rules.md`, đồng thời chấp nhận payload snake_case qua
`populate_by_name=True`. Ví dụ `trang_thai_phong` được xuất thành `trangThaiPhong`.

| Thuộc tính model | Kiểu model | Kiểu DTO |
| --- | --- | --- |
| `id_phong_thi`, `id_de_thi`, `id_hs` | `String` | `str` |
| `ma_code_phong_thi` | `String`, UNIQUE trên phòng; FK trên học sinh | `str`, đúng 6 ký tự `[A-Z0-9]` |
| `ten_lop`, `ho_va_ten` | `String` | `str`; request loại chuỗi trống |
| `thoi_gian_lam_bai` | `Integer` | `int` strict; request từ 1 đến 2147483647 phút |
| `trang_thai_phong` | `Enum(TrangThaiPhongThi)`, nullable | `Optional[TrangThaiPhongThi]` |
| `ngay_tao_phong` | `Date`, nullable | `Optional[date]` |

`RoomResponseSchema` chứa đúng các trường response yêu cầu; `id_gv` và `id_de_thi`
không được bổ sung vào response. `StudentResponseSchema` trong danh sách thí sinh
khớp toàn bộ ba cột `HocSinh`. `StudentJoinResponseSchema` thêm metadata của phòng
đúng hợp đồng join, không giả định các cột này tồn tại trong `HocSinh`.

Request sai kiểu, thiếu trường, mã sai định dạng hoặc trường ngoài schema trả
HTTP 422 trước khi thực hiện bất kỳ SQL nào. Dependency `get_room_teacher`
kiểm tra DTO trước cả truy vấn xác thực giáo viên.

Hai trường nullable của phòng phản ánh model hiện có; phòng tạo qua service luôn
được gán rõ `TrangThaiPhongThi.DANG_CHO` và ngày hiện tại theo default model.
Phòng có trạng thái NULL không được phép nhận học sinh.

## Mã phòng UNIQUE và quyền sở hữu

`generate_room_code` dùng `secrets.choice` trên chữ ASCII in hoa và số, lấy đúng
6 ký tự, kiểm tra các mã đã tồn tại với tối đa 20 ứng viên. Ràng buộc UNIQUE sẵn có
trong PostgreSQL bảo vệ khi hai request cùng chọn một mã.

Nếu INSERT gặp đúng ràng buộc `phong_thi_ma_code_phong_thi_key`, service rollback
transaction, khóa lại đề thi, sinh mã mới và thử lại tối đa 5 lần. Lỗi integrity
khác không được retry. Hết lượt trả HTTP 503; không lưu phòng lỗi.

Tạo phòng kiểm tra `DeThi.id_gv` và dùng `FOR UPDATE` trên đề để phối hợp với
API sửa/xóa đề hiện có. Xem phòng lọc cả ID phòng và giáo viên sở hữu, trả 404
cho phòng không tồn tại hoặc thuộc giáo viên khác. Join khóa phòng, trả 404 nếu
không có mã, 409 nếu phòng đóng/không có trạng thái hợp lệ. Phòng `DANG_CHO` và
`DANG_THI` đều nhận học sinh theo yêu cầu Task 3.1.

## State Output Proof

Response thực tế từ PostgreSQL được lưu tại:

- [Tạo phòng — HTTP 201](room_create_response.json): `trangThaiPhong = "DANG_CHO"`.
- [Tham gia ẩn danh — HTTP 201](student_join_response.json).
- [Thông tin phòng và danh sách thí sinh — HTTP 200](room_detail_response.json).

Script `backend/verify_room_api.py` kiểm tra trực tiếp bản ghi `KetQuaThi` sau join:
`trang_thai == TrangThaiBaiLam.DANG_THI`, `diem_so == 0.0`, và cả hai FK trỏ đúng
học sinh/phòng vừa tạo. Join không chuyển trạng thái phòng và không trả câu hỏi,
đáp án hoặc điểm qua API này.

## Transaction Safety Proof

Code đầy đủ: [services/room_service.py](../backend/app/services/room_service.py),
hàm `join_room`.

Toàn bộ kiểm tra phòng, INSERT học sinh, INSERT kết quả, kiểm tra response và
commit nằm trong cùng khối `try`. Hai lần `db.flush()` chỉ gửi INSERT và cấp ID,
không commit. Chỉ có một `db.commit()` sau khi cả hai bản ghi hợp lệ.

```python
student = HocSinh(ho_va_ten=payload.ho_va_ten, ma_code_phong_thi=room.ma_code_phong_thi)
db.add(student)
db.flush()
result = KetQuaThi(
    id_hs=student.id_hs, id_phong_thi=room.id_phong_thi,
    trang_thai=TrangThaiBaiLam.DANG_THI, diem_so=0.0,
)
db.add(result)
db.flush()
```

Các đoạn trên nằm trong `try` của `join_room`. Phần kết thúc và xử lý lỗi thực tế:

```python
        db.commit()  # Student + result are persisted together, exactly once.
        return response
    except IntegrityError as exc:
        db.rollback()
        raise HTTPException(status_code=409, detail="Student admission conflicts with existing records.") from exc
    except SQLAlchemyError as exc:
        db.rollback()
        raise HTTPException(status_code=503, detail="Room database unavailable.") from exc
    except Exception:
        db.rollback()
        raise
```

Kiểm thử không chỉ giả lập kết quả rollback: event `after_insert` gây lỗi sau khi
cả `HocSinh` và `KetQuaThi` đã xuất hiện trong transaction. Sau HTTP 503, truy vấn
xác nhận cả hai bản ghi đều biến mất, phòng vẫn tồn tại. Đã kiểm chứng điều này
trên cả SQLite có bật foreign keys và PostgreSQL thật.

Quan hệ `cascade="all, delete-orphan"` của model được giữ nguyên và kiểm thử
xóa phòng qua ORM xác nhận cả học sinh lẫn kết quả bị xóa. Task này không thêm
API xóa phòng hay điều khiển trạng thái thi.

## Chạy và kiểm thử trên PowerShell

Từ thư mục gốc dự án, dùng virtualenv `.venv` đã cài dependencies:

```powershell
Set-Location backend
..\.venv\Scripts\python.exe -m uvicorn main:app --reload --port 8000
```

Mở terminal khác, từ thư mục `backend`:

```powershell
..\.venv\Scripts\python.exe -B -m unittest discover -s tests -v
..\.venv\Scripts\python.exe -B verify_room_api.py
```

Kết quả nghiệm thu: **68/68 backend tests PASS**, gồm **22 room tests**.
Script live xác nhận tạo phòng/join/roster, quyền sở hữu, va chạm UNIQUE thực tế,
rollback sau hai INSERT. Toàn bộ dữ liệu fixture live được rollback trong một
transaction ngoài; không để lại tài khoản/đề/phòng/học sinh/kết quả kiểm thử.

Payload tạo phòng (kèm `Authorization: Bearer <JWT giáo viên>`):

```json
{"idDeThi": "<ID đề thi của giáo viên>", "tenLop": "6A", "thoiGianLamBai": 30}
```

Payload tham gia (không cần header Authorization):

```json
{"hoVaTen": "Nguyễn Văn An", "maCodePhongThi": "<mã phòng 6 ký tự>"}
```

SHA256 `backend/app/models/models.py` giữ nguyên trước/sau triển khai:
`D225DCA61619E02E2697B4CAED497E3EBED6BADBFA2D771B37CF02750721530F`.

Yêu cầu hiện tại và model dùng `DANG_THI` cho `KetQuaThi` mới. Dòng cũ `DANG_CHO`
của student join trong master plan đã được sửa cho đúng enum bài làm; phòng mới
vẫn là `DANG_CHO`.
