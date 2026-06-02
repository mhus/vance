export interface Props {
    items: T[];
    /** Optional key extractor — defaults to `item.id`, falls back to index. */
    itemKey?: (item: T, index: number) => string | number;
    /** When set, items are clickable and emit `select`. */
    selectable?: boolean;
    /** id of the currently selected item — gets highlighted. */
    selectedId?: string | number | null;
}
declare const _default: <T extends {
    id?: string | number;
}>(__VLS_props: NonNullable<Awaited<typeof __VLS_setup>>["props"], __VLS_ctx?: __VLS_PrettifyLocal<Pick<NonNullable<Awaited<typeof __VLS_setup>>, "attrs" | "emit" | "slots">>, __VLS_expose?: NonNullable<Awaited<typeof __VLS_setup>>["expose"], __VLS_setup?: Promise<{
    props: __VLS_PrettifyLocal<Pick<Partial<{}> & Omit<{
        readonly onSelect?: ((item: T) => any) | undefined;
    } & import("vue").VNodeProps & import("vue").AllowedComponentProps & import("vue").ComponentCustomProps, never>, "onSelect"> & Props & Partial<{}>> & import("vue").PublicProps;
    expose(exposed: import("vue").ShallowUnwrapRef<{}>): void;
    attrs: any;
    slots: {
        default?: (props: {
            item: T;
            index: number;
        }) => any;
    };
    emit: (e: "select", item: T) => void;
}>) => import("vue").VNode<import("vue").RendererNode, import("vue").RendererElement, {
    [key: string]: any;
}> & {
    __ctx?: Awaited<typeof __VLS_setup>;
};
export default _default;
type __VLS_PrettifyLocal<T> = {
    [K in keyof T]: T[K];
} & {};
//# sourceMappingURL=VDataList.vue.d.ts.map