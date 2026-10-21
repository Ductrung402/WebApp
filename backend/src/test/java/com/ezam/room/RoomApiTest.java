package com.ezam.room;

import com.ezam.dto.RoomDTO;
import com.ezam.util.AuthException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class RoomApiTest {
    @Test void aliasesStrictAndWhitespace(){var c=RoomDTO.create("{\"id_de_thi\":\" exam \",\"ten_lop\":\" 6A \",\"thoi_gian_lam_bai\":2147483647}");assertEquals("exam",c.idDeThi());assertEquals("6A",c.tenLop());assertEquals(Integer.MAX_VALUE,c.thoiGianLamBai());assertEquals("An",RoomDTO.join("{\"ho_va_ten\":\" An \",\"ma_code_phong_thi\":\"AB12CD\"}").hoVaTen());}
    @ParameterizedTest @ValueSource(strings={"0","-1","1.0","1e1","true","\"10\"","null","2147483648"})
    void invalidDuration(String value){assertEquals(422,assertThrows(AuthException.class,()->RoomDTO.create("{\"idDeThi\":\"e\",\"tenLop\":\"c\",\"thoiGianLamBai\":"+value+"}")).status());}
    @ParameterizedTest @ValueSource(strings={"abc123"," AB123","AB12C!","ＡB1234","AB12CD\n","","AB12CD7"})
    void codeIsExactAscii(String code){assertThrows(AuthException.class,()->new RoomDTO.Join("An",code));}
    @ParameterizedTest @ValueSource(strings={"{}","[]","null","{\"hoVaTen\":\" \",\"maCodePhongThi\":\"AB12CD\"}","{\"hoVaTen\":\"An\",\"maCodePhongThi\":\"AB12CD\",\"idHs\":\"injected\"}","{\"hoVaTen\":\"An\",\"ho_va_ten\":\"An\",\"maCodePhongThi\":\"AB12CD\"}"})
    void invalidJoin(String json){assertEquals(422,assertThrows(AuthException.class,()->RoomDTO.join(json)).status());}
}
