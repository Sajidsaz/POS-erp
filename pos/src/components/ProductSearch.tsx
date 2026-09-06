import { useEffect, useRef, useState } from "react";
import { ApiClientError } from "../api/client";
import { Api } from "../api/endpoints";
import { formatMoney } from "../api/money";
import type { VariantView } from "../api/types";

interface Tile {
  variant: VariantView;
  productName: string;
}

/**
 * The till's item finder. Typing runs a debounced catalog search; pressing Enter first tries
 * an exact barcode lookup (how a hardware scanner delivers a code) and only falls back to the
 * text results if there's no match.
 */
export function ProductSearch({
  api,
  currency,
  onPick,
}: {
  api: Api;
  currency?: string;
  onPick: (variant: VariantView) => void;
}) {
  const [query, setQuery] = useState("");
  const [tiles, setTiles] = useState<Tile[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);
  const seq = useRef(0);

  useEffect(() => {
    inputRef.current?.focus();
  }, []);

  useEffect(() => {
    const q = query.trim();
    if (q.length < 2) {
      setTiles([]);
      return;
    }
    const mine = ++seq.current;
    setLoading(true);
    const handle = setTimeout(async () => {
      try {
        const products = await api.searchProducts(q, 24);
        if (mine !== seq.current) return;
        const flat: Tile[] = [];
        for (const p of products) {
          for (const v of p.variants) {
            if (v.active) flat.push({ variant: v, productName: p.name });
          }
        }
        setTiles(flat);
        setError(null);
      } catch (e) {
        if (mine === seq.current)
          setError(e instanceof ApiClientError ? e.message : "Search failed");
      } finally {
        if (mine === seq.current) setLoading(false);
      }
    }, 200);
    return () => clearTimeout(handle);
  }, [query, api]);

  const onEnter = async (e: React.KeyboardEvent) => {
    if (e.key !== "Enter") return;
    const code = query.trim();
    if (!code) return;
    try {
      const variant = await api.findByBarcode(code);
      onPick(variant);
      setQuery("");
      setTiles([]);
    } catch {
      // Not a barcode — leave the text results as they are.
    }
  };

  const pick = (t: Tile) => {
    onPick(t.variant);
    setQuery("");
    setTiles([]);
    inputRef.current?.focus();
  };

  return (
    <>
      <div className="search-bar">
        <input
          ref={inputRef}
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          onKeyDown={onEnter}
          placeholder="Scan barcode or search products…"
        />
      </div>
      <div className="results">
        {error && <div className="empty-hint">{error}</div>}
        {!error && tiles.length === 0 && (
          <div className="empty-hint">
            {loading ? "Searching…" : "Scan an item or type to search the catalog."}
          </div>
        )}
        {tiles.map((t) => (
          <button className="product-tile" key={t.variant.id} onClick={() => pick(t)}>
            <div className="name">{t.variant.name || t.productName}</div>
            <div className="sku">{t.variant.sku}</div>
            <div className="price">{formatMoney(t.variant.price, currency)}</div>
          </button>
        ))}
      </div>
    </>
  );
}
