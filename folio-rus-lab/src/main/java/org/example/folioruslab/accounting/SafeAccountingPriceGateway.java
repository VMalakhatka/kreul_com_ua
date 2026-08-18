package org.example.folioruslab.accounting;

import java.util.List;

interface SafeAccountingPriceGateway {

    PreviewSession open(int warehouseId);

    interface PreviewSession extends AutoCloseable {
        PreviewScope scope();

        SkuPreview previewOne(String sku);

        @Override
        void close();
    }

    record PreviewScope(int warehouseId, List<String> skus) {
        public PreviewScope {
            skus = List.copyOf(skus);
        }
    }

    record SkuPreview(
            String sku,
            String nextSku,
            int returnCode,
            String negativeDate,
            SafeAccountingPriceProblem problem
    ) {
    }
}
