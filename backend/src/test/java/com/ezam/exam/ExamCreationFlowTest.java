package com.ezam.exam;

import com.ezam.dto.ExamDTO;
import com.ezam.service.GeminiService;
import com.ezam.util.AuthException;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExamCreationFlowTest {
    @Test void previewDraftRoundTripsThroughManualContractAndReviewDoesNotMutateGeneratedQuestion() {
        var generated=GeminiService.validateQuizJson("["+GeminiServiceTest.QUESTION+"]",1).get(0);
        var draft=new ExamDTO.Manual("Title",null,"TEXT"," Source ",java.util.List.of(generated));
        var json=new GsonBuilder().serializeNulls().create();
        JsonObject edited=JsonParser.parseString(json.toJson(draft)).getAsJsonObject();
        edited.getAsJsonArray("cauHoiList").get(0).getAsJsonObject().addProperty("noiDung","Reviewed question");
        edited.getAsJsonArray("cauHoiList").get(0).getAsJsonObject().addProperty("dapAnDung","B");
        var finalPayload=ExamDTO.manual(edited.toString());
        assertEquals("Reviewed question",finalPayload.cauHoiList().get(0).noiDung());assertEquals("B",finalPayload.cauHoiList().get(0).dapAnDung());
        assertEquals("A",generated.dapAnDung());assertEquals(" Source ",finalPayload.noiDungTaiLieu());
        edited.addProperty("soLuongCauHoi",999);assertThrows(AuthException.class,()->ExamDTO.manual(edited.toString()));
    }
}
