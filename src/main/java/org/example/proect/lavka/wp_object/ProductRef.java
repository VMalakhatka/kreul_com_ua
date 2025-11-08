package org.example.proect.lavka.wp_object;

// что ImageAttachment должен «знать» о товаре для генерации alt/title/slug
public record ProductRef(
        Long   productId,
        String sku,
        String name,          // полное имя товара (то, что видит клиент)
        String brand,         // опц.
        String lineOrColor,   // опц. «Серия/цвет»
        String volumeOrSize,  // опц. «50 мл», «23×33 см»
        String angle,         // опц. «вид спереди», «ракурс 45°»
        String imgFileName    // имя файла из MSSQL/DTO (например "ser_1110.jpg")
) {}