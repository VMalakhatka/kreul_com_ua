package org.example.proect.lavka.controller;
import org.example.proect.lavka.dao.folio.FolioProductPackagingDao;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.util.List;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class FolioProductPackagingControllerTest {
 @Test void validatesAuthAndBoundsBeforeReading(){
  var dao=mock(FolioProductPackagingDao.class);var api=new FolioProductPackagingController(dao);
  var req=new FolioProductPackagingController.Request(List.of("A"));
  assertEquals(503,api.read(req,null).getStatusCode().value());
  ReflectionTestUtils.setField(api,"token","fixture-only");
  assertEquals(401,api.read(req,"wrong").getStatusCode().value());
  assertEquals(400,api.read(new FolioProductPackagingController.Request(Collections.nCopies(501,"A")),"fixture-only").getStatusCode().value());
  assertEquals(400,api.read(new FolioProductPackagingController.Request(List.of(" ")),"fixture-only").getStatusCode().value());
  verifyNoInteractions(dao);
  when(dao.read(List.of("A"))).thenReturn(List.of(new FolioProductPackagingDao.Item("A",new BigDecimal("5"))));
  var result=api.read(new FolioProductPackagingController.Request(List.of("A","A")),"fixture-only");
  assertEquals(200,result.getStatusCode().value());assertEquals("no-store",result.getHeaders().getCacheControl());
  assertEquals(new BigDecimal("5"),result.getBody().get(0).unitsPerPack());verify(dao).read(List.of("A"));
 }
}
