# Stock price lookup

`POST /admin/stock/prices` reads the configured price warehouse; it does not
modify Folio. The request contains Woo `skus` and a `priceMap` from Woo role to
Folio price name. Retail comes from `SCL_ARTC.CENA_ARTIC`; role prices come
from `SCL_PRIC` (RUB_PRICE, falling back to VALT_PRICE only when null).

Leading and trailing spaces do not affect either SKU lookup. Interior spaces
remain significant. Retail retains its existing en-dash to hyphen lookup.
Each response item echoes the original requested SKU in request order.
Missing retail or a missing mapped role price is null, never an inferred zero.
Consumers must preserve the corresponding saved price on missing data and
accept an explicit numeric zero. Deploy the Woo consumer guard before Java.
