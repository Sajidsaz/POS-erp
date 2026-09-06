import { useCallback, useEffect, useRef, useState } from "react";
import { ApiClientError } from "../api/client";
import { Api } from "../api/endpoints";
import type { CalculatedCartView, CartLineInput, Money, VariantView } from "../api/types";

/** A line as the till holds it locally; prices/tax/totals are always taken from the server. */
export interface CartLine {
  variantId: string;
  sku: string;
  productName: string;
  quantity: string; // decimal string
  unitPriceOverride?: Money | null;
  discountAmount?: Money | null;
  /** Catalog price, shown optimistically until the calculate call reconciles it. */
  catalogPrice: Money;
}

export interface UseCart {
  lines: CartLine[];
  calculated: CalculatedCartView | null;
  calculating: boolean;
  error: string | null;
  addVariant: (v: VariantView) => void;
  addLine: (line: CartLine) => void;
  setQuantity: (variantId: string, quantity: string) => void;
  bumpQuantity: (variantId: string, delta: number) => void;
  setDiscount: (variantId: string, discount: Money | null) => void;
  removeLine: (variantId: string) => void;
  clear: () => void;
  toInputs: () => CartLineInput[];
}

export function useCart(api: Api, shopId: string): UseCart {
  const [lines, setLines] = useState<CartLine[]>([]);
  const [calculated, setCalculated] = useState<CalculatedCartView | null>(null);
  const [calculating, setCalculating] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const seq = useRef(0);

  const toInputs = useCallback(
    (): CartLineInput[] =>
      lines.map((l) => ({
        variantId: l.variantId,
        quantity: l.quantity,
        unitPrice: l.unitPriceOverride ?? null,
        discountAmount: l.discountAmount ?? null,
      })),
    [lines],
  );

  // Recalculate against the server whenever the cart changes (debounced), so tax rounding
  // (D4) and price resolution stay server-owned. Stale responses are dropped by sequence.
  useEffect(() => {
    if (lines.length === 0) {
      setCalculated(null);
      setError(null);
      setCalculating(false);
      return;
    }
    const mine = ++seq.current;
    setCalculating(true);
    const handle = setTimeout(async () => {
      try {
        const view = await api.calculateCart({ shopId, lines: toInputs() });
        if (mine === seq.current) {
          setCalculated(view);
          setError(null);
        }
      } catch (e) {
        if (mine === seq.current) {
          const msg = e instanceof ApiClientError ? e.message : "Could not price the cart";
          setError(msg);
        }
      } finally {
        if (mine === seq.current) setCalculating(false);
      }
    }, 180);
    return () => clearTimeout(handle);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [lines, shopId]);

  const addLine = useCallback((line: CartLine) => {
    setLines((prev) => {
      const existing = prev.find((l) => l.variantId === line.variantId);
      if (existing) {
        return prev.map((l) =>
          l.variantId === line.variantId
            ? { ...l, quantity: addQty(l.quantity, line.quantity) }
            : l,
        );
      }
      return [...prev, line];
    });
  }, []);

  const addVariant = useCallback(
    (v: VariantView) => {
      addLine({
        variantId: v.id,
        sku: v.sku,
        productName: v.name,
        quantity: "1",
        catalogPrice: v.price,
      });
    },
    [addLine],
  );

  const setQuantity = useCallback((variantId: string, quantity: string) => {
    setLines((prev) =>
      prev.map((l) => (l.variantId === variantId ? { ...l, quantity } : l)),
    );
  }, []);

  const bumpQuantity = useCallback((variantId: string, delta: number) => {
    setLines((prev) =>
      prev.flatMap((l) => {
        if (l.variantId !== variantId) return [l];
        const next = Math.max(0, Math.round((Number(l.quantity) + delta) * 10000) / 10000);
        if (next === 0) return [];
        return [{ ...l, quantity: String(next) }];
      }),
    );
  }, []);

  const setDiscount = useCallback((variantId: string, discount: Money | null) => {
    setLines((prev) =>
      prev.map((l) => (l.variantId === variantId ? { ...l, discountAmount: discount } : l)),
    );
  }, []);

  const removeLine = useCallback((variantId: string) => {
    setLines((prev) => prev.filter((l) => l.variantId !== variantId));
  }, []);

  const clear = useCallback(() => {
    setLines([]);
    setCalculated(null);
    setError(null);
  }, []);

  return {
    lines,
    calculated,
    calculating,
    error,
    addVariant,
    addLine,
    setQuantity,
    bumpQuantity,
    setDiscount,
    removeLine,
    clear,
    toInputs,
  };
}

function addQty(a: string, b: string): string {
  return String(Math.round((Number(a) + Number(b)) * 10000) / 10000);
}
