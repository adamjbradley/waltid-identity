'use client';

import WaltIcon from "@/components/walt/logo/WaltIcon";

type Props = {
  id: string;
  title: string;
  description?: string;
  onClick: (id: string) => void;
};

export default function Credential({
  id,
  title,
  description,
  onClick,
}: Props) {
  return (
    <div onClick={() => onClick(id)}>
      <div
        className="drop-shadow-sm hover:drop-shadow-xl flex flex-col rounded-xl py-7 px-8 text-gray-100 h-[225px] w-[360px] cursor-pointer overflow-hidden bg-gradient-to-r from-primary-400 to-primary-600 hover:from-primary-500 hover:to-primary-700 transition-all duration-200"
      >
        <div className="flex flex-row">
          <WaltIcon height={35} width={35} outline type="white" />
        </div>
        <div className="mb-8 mt-12">
          <h6 className={'text-2xl font-bold '}>
            {title.length > 20 ? title.substring(0, 20) + '...' : title}
          </h6>
          <span>{description}</span>
        </div>
      </div>
    </div>
  );
}
