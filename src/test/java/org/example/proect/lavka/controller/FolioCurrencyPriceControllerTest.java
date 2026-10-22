package org.example.proect.lavka.controller;
import org.example.proect.lavka.service.folio.FolioCurrencyPriceService;
import org.example.proect.lavka.property.LavkaApiProperties;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class FolioCurrencyPriceControllerTest {
 @Test void authenticationIsRequiredForEveryRoute(){var s=mock(FolioCurrencyPriceService.class);var p=mock(LavkaApiProperties.class);var c=new FolioCurrencyPriceController(s,p);var request=new FolioCurrencyPriceController.Request(List.of(),"preview-token",true);when(p.getToken()).thenReturn("test-secret");for(var a:List.of("preview","apply","status")){assertEquals(401,c.call(a,request,null).getStatusCode().value());assertEquals(401,c.call(a,request,"wrong").getStatusCode().value());}verifyNoInteractions(s);}
}
